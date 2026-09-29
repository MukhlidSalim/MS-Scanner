package com.example.engine.pdf

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.engine.cv.ImageProcessor
import com.example.engine.ocr.OcrLayoutStore
import com.example.engine.ocr.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class PdfExportConfig(
    val title: String,
    val pageSize: PageSizePreset = PageSizePreset.A4,
    val compression: CompressionPreset = CompressionPreset.HIGH,
    val includeSearchableText: Boolean = true,
    val includePageNumbers: Boolean = true,
    val watermarkText: String? = null,
    /** When set, the PDF is encrypted (AES-128): the password is needed to open it. */
    val password: String? = null
)

/**
 * PDF generation + the single Save / Share / Print layer of the app.
 *
 * Storage model (no storage permission needed on any supported version, minSdk 24):
 *  - Working files: filesDir/exports (shared through the app FileProvider).
 *  - "Save as PDF": Android 10+ -> MediaStore Downloads/MS Scanner. Older -> caller uses SAF CreateDocument.
 *  - "Save as Images": Android 10+ -> MediaStore Pictures/MS Scanner. Older -> caller uses SAF folder picker.
 */
object PdfEngine {

    private const val PUBLIC_FOLDER = "MS Scanner"
    private const val EXPORT_DIR = "exports"
    private const val SHARE_IMAGES_DIR = "share_images"

    /** File-system safe name that KEEPS Arabic / Unicode letters (the old regex turned Arabic titles into "____"). */
    fun safeFileName(title: String, fallback: String = "Document"): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(80)
            .ifBlank { fallback }

    private fun exportDir(context: Context): File =
        File(context.filesDir, EXPORT_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Estimates file size in bytes based on page count and compression preset
     */
    fun estimatePdfSizeBytes(pageCount: Int, preset: CompressionPreset): Long {
        val perPageBytes = when (preset) {
            CompressionPreset.LOW -> 120_000L
            CompressionPreset.MEDIUM -> 350_000L
            CompressionPreset.HIGH -> 800_000L
            CompressionPreset.MAXIMUM -> 2_200_000L
        }
        return perPageBytes * pageCount.coerceAtLeast(1)
    }

    fun formatEstimatedSize(bytes: Long): String {
        return if (bytes < 1024 * 1024) {
            "${bytes / 1024} KB"
        } else {
            String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024f * 1024f))
        }
    }

    /**
     * Prints (or "Save as PDF" through the system print dialog). [context] must be an Activity context.
     */
    fun printScannedDocuments(
        context: Context,
        documentTitle: String,
        imagePaths: List<String>
    ) {
        val paths = imagePaths.filter { it.isNotBlank() && File(it).exists() }
        if (paths.isEmpty()) return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
        val safeTitle = safeFileName(documentTitle, "Scanned_Document")
        val adapter = ScannedImagePrintAdapter(context.applicationContext, safeTitle, paths)
        val printAttributes = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
            .build()
        try {
            printManager.print(safeTitle, adapter, printAttributes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Generates a multi-page PDF, one bitmap in memory at a time. Runs on Dispatchers.IO.
     */
    suspend fun generatePdf(
        context: Context,
        pagePathsAndOcr: List<Pair<String, String>>, // (imagePath, ocrText)
        config: PdfExportConfig
    ): File = withContext(Dispatchers.IO) {
        cleanupExports(context)
        val pdfDocument = PdfDocument()
        val safeTitle = safeFileName(config.title)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(exportDir(context), "${safeTitle}_${timeStamp}.pdf")

        // The pixel size stored in the PDF is what drives file size, so the preset controls it directly.
        // (The previous JPEG encode/decode round-trip only doubled memory: PdfDocument re-encodes pixels.)
        val maxDimension = when (config.compression) {
            CompressionPreset.LOW -> 1000
            CompressionPreset.MEDIUM -> 1400
            CompressionPreset.HIGH -> 2000
            CompressionPreset.MAXIMUM -> 3000
        }
        var renderedPageCount = 0
        try {
            for (i in pagePathsAndOcr.indices) {
                val (path, ocrText) = pagePathsAndOcr[i]
                val bitmap = ImageProcessor.loadBitmapFromFile(path, maxDim = maxDimension) ?: continue
                try {
                    val (pageWidth, pageHeight) = when (config.pageSize) {
                        PageSizePreset.A4 -> Pair(595, 842)
                        PageSizePreset.LETTER -> Pair(612, 792)
                        PageSizePreset.LEGAL -> Pair(612, 1008)
                        PageSizePreset.FIT_ORIGINAL -> {
                            val maxPt = 842
                            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                            if (aspect > 1f) Pair(maxPt, (maxPt / aspect).toInt().coerceAtLeast(1))
                            else Pair((maxPt * aspect).toInt().coerceAtLeast(1), maxPt)
                        }
                    }
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, renderedPageCount + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    val margin = if (config.pageSize == PageSizePreset.FIT_ORIGINAL) 0 else 10
                    val footer = if (config.includePageNumbers) 10 else 0
                    val usableW = (pageWidth - margin * 2).coerceAtLeast(1)
                    val usableH = (pageHeight - margin * 2 - footer).coerceAtLeast(1)
                    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                    val pageAspect = usableW.toFloat() / usableH.toFloat()
                    val drawRect = if (bitmapAspect > pageAspect) {
                        val drawH = (usableW / bitmapAspect).toInt()
                        val offsetY = margin + (usableH - drawH) / 2
                        Rect(margin, offsetY, margin + usableW, offsetY + drawH)
                    } else {
                        val drawW = (usableH * bitmapAspect).toInt()
                        val offsetX = margin + (usableW - drawW) / 2
                        Rect(offsetX, margin, offsetX + drawW, margin + usableH)
                    }
                    canvas.drawBitmap(bitmap, null, drawRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

                    if (config.includeSearchableText) {
                        val layout = OcrLayoutStore.load(path)
                        if (!layout.isNullOrEmpty()) {
                            drawPositionedTextLayer(canvas, layout, drawRect)
                        } else if (ocrText.isNotBlank()) {
                            drawFallbackTextLayer(canvas, ocrText, drawRect)
                        }
                    }

                    val watermark = config.watermarkText
                    if (!watermark.isNullOrBlank()) {
                        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(45, 120, 120, 120)
                            textSize = 38f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.save()
                        canvas.rotate(-45f, (pageWidth / 2).toFloat(), (pageHeight / 2).toFloat())
                        canvas.drawText(watermark, (pageWidth / 2).toFloat(), (pageHeight / 2).toFloat(), wmPaint)
                        canvas.restore()
                    }

                    if (config.includePageNumbers) {
                        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.DKGRAY
                            textSize = 9f
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.drawText("${i + 1} / ${pagePathsAndOcr.size}", (pageWidth / 2).toFloat(), (pageHeight - 4).toFloat(), numPaint)
                    }
                    pdfDocument.finishPage(page)
                    renderedPageCount++
                } finally {
                    bitmap.recycle()
                }
            }
            if (renderedPageCount == 0) {
                throw IllegalArgumentException("No readable page images were available for PDF export")
            }
            FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
        } catch (e: Throwable) {
            outputFile.delete()
            if (e is OutOfMemoryError) throw IllegalStateException("Not enough memory to build the PDF. Try a lower quality.", e)
            throw e
        } finally {
            pdfDocument.close()
        }
        val pwd = config.password?.takeIf { it.isNotBlank() }
        if (pwd != null) {
            try {
                encryptInPlace(context, outputFile, pwd)
            } catch (e: Throwable) {
                // Never hand out an unprotected file when protection was requested.
                outputFile.delete()
                throw IllegalStateException("Could not password-protect the PDF", e)
            }
        }
        outputFile
    }

    /**
     * AES-128 password protection with PdfBox-Android. The same password opens the document and
     * grants all permissions (print / copy); a random owner password would lock the user out of editing.
     */
    private fun encryptInPlace(context: Context, file: File, password: String) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        val tmp = File(file.parentFile, file.name + ".enc")
        try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    password, password, com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
                ).apply { encryptionKeyLength = 128 }
                doc.protect(policy)
                doc.save(tmp)
            }
            if (!file.delete() || !tmp.renameTo(file)) throw java.io.IOException("Could not replace the PDF")
        } finally {
            tmp.delete()
        }
    }

    /**
     * Invisible text drawn exactly over each recognized line, so selecting / searching in any PDF reader
     * highlights the right place. Alpha 1/255 (not 0): fully transparent drawing is skipped by the PDF
     * backend, which is why the previous "searchable layer" was never actually written.
     */
    private fun drawPositionedTextLayer(canvas: Canvas, lines: List<OcrLine>, image: Rect) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(1, 0, 0, 0) }
        val iw = image.width().toFloat()
        val ih = image.height().toFloat()
        for (line in lines) {
            val left = image.left + line.left * iw
            val right = image.left + line.right * iw
            val top = image.top + line.top * ih
            val bottom = image.top + line.bottom * ih
            val height = bottom - top
            val width = right - left
            if (height < 1.5f || width < 1.5f) continue
            paint.textScaleX = 1f
            paint.textSize = height * 0.82f
            val measured = paint.measureText(line.text)
            if (measured <= 0f) continue
            paint.textScaleX = (width / measured).coerceIn(0.2f, 4f)
            canvas.drawText(line.text, left, bottom - height * 0.18f, paint)
        }
    }

    /** Pages analysed before layouts existed: text is still searchable, just not positioned. */
    private fun drawFallbackTextLayer(canvas: Canvas, ocrText: String, image: Rect) {
        val paint = Paint().apply {
            color = android.graphics.Color.argb(1, 0, 0, 0)
            textSize = 8f
        }
        var y = image.top + 10f
        for (line in ocrText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(200)) {
            if (y > image.bottom) break
            canvas.drawText(line, image.left + 2f, y, paint)
            y += 10f
        }
    }

    /** Deletes stale exported / shared files so the exports folder never grows forever. */
    fun cleanupExports(context: Context, maxAgeMs: Long = 24L * 60 * 60 * 1000) {
        runCatching {
            val now = System.currentTimeMillis()
            exportDir(context).walkBottomUp().forEach { f ->
                if (f.isFile && now - f.lastModified() > maxAgeMs) f.delete()
            }
        }
    }

    fun getFileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun startChooser(context: Context, target: Intent, title: String) {
        val chooser = Intent.createChooser(target, title)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Share a PDF file via ACTION_SEND. Returns false if nothing could be started.
     */
    fun sharePdf(context: Context, pdfFile: File, chooserTitle: String = "Share PDF via"): Boolean {
        return try {
            val uri = getFileProviderUri(context, pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, pdfFile.nameWithoutExtension)
                clipData = ClipData.newRawUri(pdfFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooser(context, intent, chooserTitle)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Share one or many files (images, txt, doc…) in ONE chooser. Multiple URIs are also put in ClipData,
     * otherwise the receiving app is only granted access to the first file.
     */
    fun shareFiles(context: Context, files: List<File>, mimeType: String, chooserTitle: String = "Share"): Boolean {
        val existing = files.filter { it.exists() && it.length() > 0 }
        if (existing.isEmpty()) return false
        return try {
            val uris = existing.map { getFileProviderUri(context, it) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
            }
            intent.type = mimeType
            val clip = ClipData.newRawUri(existing.first().name, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startChooser(context, intent, chooserTitle)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Copies page images to readable names ("<title>_p01.jpg") in the export folder for sharing.
     * The folder is cleared first, so repeated shares never accumulate files.
     */
    suspend fun prepareImagesForShare(context: Context, imagePaths: List<String>, title: String): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(exportDir(context), SHARE_IMAGES_DIR)
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
            val base = safeFileName(title)
            val digits = imagePaths.size.toString().length.coerceAtLeast(2)
            imagePaths.mapIndexedNotNull { i, path ->
                val src = File(path)
                if (!src.exists()) return@mapIndexedNotNull null
                val dst = File(dir, "${base}_p${(i + 1).toString().padStart(digits, '0')}.jpg")
                runCatching { src.copyTo(dst, overwrite = true) }.getOrNull()
            }
        }

    /** Real PNG export of one page (the old code shared the JPEG with an image/png MIME type). */
    suspend fun exportPng(context: Context, imagePath: String, title: String): File? = withContext(Dispatchers.IO) {
        val bmp = ImageProcessor.loadBitmapFromFile(imagePath, 4096) ?: return@withContext null
        try {
            val out = File(exportDir(context), "${safeFileName(title)}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        } finally {
            bmp.recycle()
        }
    }

    suspend fun exportText(context: Context, text: String, title: String, extension: String): File =
        withContext(Dispatchers.IO) {
            File(exportDir(context), "${safeFileName(title)}.$extension").apply { writeText(text) }
        }

    // --------------------------------------------------------------------------- save to device

    /**
     * Android 10+: writes the PDF to Downloads/MS Scanner through MediaStore (no permission).
     * Returns null on older versions or failure; the caller then falls back to SAF (CreateDocument).
     */
    suspend fun savePdfToDownloads(context: Context, pdfFile: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val resolver = context.contentResolver
        val name = safeFileName(displayName).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { out -> pdfFile.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Cannot open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Android 10+: saves page images to Pictures/MS Scanner (visible in the Gallery). Returns the number
     * saved, or -1 when the platform needs the SAF folder picker instead (Android 7–9).
     */
    suspend fun saveImagesToGallery(context: Context, imagePaths: List<String>, title: String): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext -1
        val resolver = context.contentResolver
        val base = safeFileName(title)
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        var saved = 0
        imagePaths.forEachIndexed { i, path ->
            val src = File(path)
            if (!src.exists()) return@forEachIndexed
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_${stamp}_p${i + 1}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$PUBLIC_FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                ?: return@forEachIndexed
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Cannot open output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                saved++
            } catch (e: Exception) {
                e.printStackTrace()
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        saved
    }

    /** Android 7–9 fallback: writes images into a folder the user picked (ACTION_OPEN_DOCUMENT_TREE). */
    suspend fun saveImagesToTree(context: Context, treeUri: Uri, imagePaths: List<String>, title: String): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent = try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            } catch (e: Exception) {
                return@withContext 0
            }
            val base = safeFileName(title)
            var saved = 0
            imagePaths.forEachIndexed { i, path ->
                val src = File(path)
                if (!src.exists()) return@forEachIndexed
                try {
                    val doc = DocumentsContract.createDocument(resolver, parent, "image/jpeg", "${base}_p${i + 1}.jpg")
                        ?: return@forEachIndexed
                    resolver.openOutputStream(doc)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    saved++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            saved
        }

    /**
     * Open and view a PDF file in an external viewer using ACTION_VIEW with FileProvider
     */
    fun openPdf(context: Context, pdfFile: File, chooserTitle: String = "Open PDF with") {
        try {
            val uri = getFileProviderUri(context, pdfFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooser(context, intent, chooserTitle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Legacy: save an exported PDF into app-managed external Documents storage.
     * Kept for existing callers; new code uses [savePdfToDownloads] (visible to the user).
     */
    fun savePdfToStorage(
        context: Context,
        pdfFile: File,
        displayName: String
    ): Pair<Uri?, String?> {
        val safeName = safeFileName(displayName, "Exported_Document").let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        return try {
            val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, EXPORT_DIR)
            exportDir.mkdirs()
            val targetFile = File(exportDir, safeName)
            pdfFile.copyTo(targetFile, overwrite = true)
            Pair(getFileProviderUri(context, targetFile), targetFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, null)
        }
    }

    /**
     * Copy file content to an output URI chosen by the user via SAF (CreateDocument). Call off the main thread.
     */
    fun copyPdfToUri(context: Context, pdfFile: File, targetUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                pdfFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Copies an incoming content/file URI to a persistent cache file and extracts its original display name.
     */
    suspend fun copyUriToLocalPdf(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            var displayName = "document_${System.currentTimeMillis()}.pdf"
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = it.getString(nameIndex)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                    }
                }
            } else if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) displayName = File(path).name
            }
            if (!displayName.endsWith(".pdf", ignoreCase = true)) displayName = "$displayName.pdf"
            val safeName = safeFileName(displayName, "document.pdf")
            val targetDir = File(context.cacheDir, "incoming_pdfs").apply { if (!exists()) mkdirs() }
            val targetFile = File(targetDir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            if (targetFile.exists() && targetFile.length() > 0L) targetFile else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts each page of an existing PDF file into images.
     * Returns List<Pair<rawImagePath, processedImagePath>>
     */
    suspend fun convertPdfToPages(
        context: Context,
        pdfFile: File,
        maxPages: Int = 50,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<String, String>>()
        if (!pdfFile.exists() || pdfFile.length() == 0L) return@withContext result
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        try {
            pfd = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd)
            val pageCount = minOf(renderer.pageCount, maxPages)
            val targetWidth = 1600
            for (i in 0 until pageCount) {
                onProgress?.invoke(i + 1, pageCount)
                val page = renderer.openPage(i)
                val bitmap: Bitmap
                try {
                    val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                    val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
                    bitmap = try {
                        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    } catch (oom: OutOfMemoryError) {
                        Bitmap.createBitmap(targetWidth / 2, (targetHeight / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    }
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }
                try {
                    val path = ImageProcessor.saveBitmapToFile(context, bitmap, "pdf_import_p${i + 1}_")
                    result.add(Pair(path, path))
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        result
    }
}
