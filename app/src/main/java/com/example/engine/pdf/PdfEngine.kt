package com.example.engine.pdf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    val watermarkText: String? = null
)

object PdfEngine {

    /**
     * Estimates file size in bytes based on page count and compression preset
     */
    fun estimatePdfSizeBytes(pageCount: Int, preset: CompressionPreset): Long {
        val perPageBytes = when (preset) {
            CompressionPreset.LOW -> 120_000L      // ~120 KB / page
            CompressionPreset.MEDIUM -> 350_000L   // ~350 KB / page
            CompressionPreset.HIGH -> 800_000L     // ~800 KB / page
            CompressionPreset.MAXIMUM -> 2_200_000L // ~2.2 MB / page
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
     * Prints or exports to PDF using the Android Print framework (Save as PDF).
     */
    fun printScannedDocuments(
        context: Context,
        documentTitle: String,
        imagePaths: List<String>
    ) {
        if (imagePaths.isEmpty()) return
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
        val safeTitle = documentTitle.replace("[^a-zA-Z0-9_\\-\\s]".toRegex(), "_").trim().ifBlank { "Scanned_Document" }
        val adapter = ScannedImagePrintAdapter(context, safeTitle, imagePaths)
        val printAttributes = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printManager.print("$safeTitle PDF", adapter, printAttributes)
    }

    /**
     * Generates a multi-page PDF document with low memory streaming consumption.
     */
    suspend fun generatePdf(
        context: Context,
        pagePathsAndOcr: List<Pair<String, String>>, // (imagePath, ocrText)
        config: PdfExportConfig
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        val exportDir = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
        val safeTitle = config.title.replace("[^a-zA-Z0-9_\\-\\s]".toRegex(), "_").trim().ifBlank { "Document" }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputFile = File(exportDir, "${safeTitle}_${timeStamp}.pdf")

        // Max dimension for rendering based on compression preset to save memory and regulate file size
        val maxDimension = when (config.compression) {
            CompressionPreset.LOW -> 1100     // Small PDF size for instant email/messaging
            CompressionPreset.MEDIUM -> 1600  // Balanced size & clarity
            CompressionPreset.HIGH -> 2200    // High resolution for printing & archiving
            CompressionPreset.MAXIMUM -> 3200 // Lossless original scan detail
        }

        var renderedPageCount = 0
        try {
            for (i in pagePathsAndOcr.indices) {
                val (path, ocrText) = pagePathsAndOcr[i]
                val originalBitmap = ImageProcessor.loadBitmapFromFile(path, maxDim = maxDimension) ?: continue

                // Standard PDF point dimensions (72 pt/inch)
                val (pageWidth, pageHeight) = when (config.pageSize) {
                    PageSizePreset.A4 -> Pair(595, 842)
                    PageSizePreset.LETTER -> Pair(612, 792)
                    PageSizePreset.LEGAL -> Pair(612, 1008)
                    PageSizePreset.FIT_ORIGINAL -> {
                        val maxPt = 842
                        val aspect = originalBitmap.width.toFloat() / originalBitmap.height.toFloat().coerceAtLeast(1f)
                        if (aspect > 1f) Pair(maxPt, (maxPt / aspect).toInt())
                        else Pair((maxPt * aspect).toInt(), maxPt)
                    }
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Compress bitmap with smart JPEG compression to keep output PDF compact
                val renderBitmap = if (config.compression != CompressionPreset.MAXIMUM) {
                    val quality = config.compression.qualityPercent
                    val stream = ByteArrayOutputStream()
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    val bytes = stream.toByteArray()
                    val compressedBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    compressedBmp ?: originalBitmap
                } else {
                    originalBitmap
                }

                // Fit bitmap centered inside the page margins (10pt margin)
                val margin = 10
                val usableW = pageWidth - margin * 2
                val usableH = pageHeight - margin * 2

                val bitmapAspect = renderBitmap.width.toFloat() / renderBitmap.height.toFloat().coerceAtLeast(1f)
                val pageAspect = usableW.toFloat() / usableH.toFloat()

                val drawRect = if (bitmapAspect > pageAspect) {
                    val drawW = usableW
                    val drawH = (usableW / bitmapAspect).toInt()
                    val offsetY = margin + (usableH - drawH) / 2
                    Rect(margin, offsetY, margin + drawW, offsetY + drawH)
                } else {
                    val drawH = usableH
                    val drawW = (usableH * bitmapAspect).toInt()
                    val offsetX = margin + (usableW - drawW) / 2
                    Rect(offsetX, margin, offsetX + drawW, margin + drawH)
                }

                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(renderBitmap, null, drawRect, paint)

                // Searchable OCR text layer
                if (config.includeSearchableText && ocrText.isNotBlank()) {
                    val ocrPaint = Paint().apply {
                        color = android.graphics.Color.TRANSPARENT
                        alpha = 0
                        textSize = 8f
                    }
                    val words = ocrText.split("\\s+".toRegex()).take(200)
                    var textY = margin + 14f
                    for (chunk in words.chunked(10)) {
                        if (textY < pageHeight - margin) {
                            canvas.drawText(chunk.joinToString(" "), margin.toFloat(), textY, ocrPaint)
                            textY += 12f
                        }
                    }
                }

                // Watermark overlay if configured
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

                // Page numbering footer
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

                // Immediately recycle to prevent OutOfMemory on multi-page batches
                if (originalBitmap != renderBitmap) {
                    renderBitmap.recycle()
                }
                originalBitmap.recycle()
            }

            if (renderedPageCount == 0) {
                throw IllegalArgumentException("No readable page images were available for PDF export")
            }

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        } finally {
            pdfDocument.close()
        }

        outputFile
    }

    /**
     * Get a secure FileProvider URI for a PDF file
     */
    fun getFileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }

    /**
     * Share a PDF file via standard Android ACTION_SEND intent with FileProvider
     */
    fun sharePdf(context: Context, pdfFile: File, chooserTitle: String = "Share PDF via") {
        try {
            val uri = getFileProviderUri(context, pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, pdfFile.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, chooserTitle)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            val chooser = Intent.createChooser(intent, chooserTitle)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Save PDF file to the public Downloads / MS_Scanner folder
     * Returns Pair(Uri?, DisplayPath?)
     */
    fun savePdfToStorage(
        context: Context,
        pdfFile: File,
        displayName: String
    ): Pair<Uri?, String?> {
        val safeName = if (displayName.endsWith(".pdf", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.pdf"
        }.replace("[^a-zA-Z0-9._\\-\\s]".toRegex(), "_").trim().ifBlank { "Exported_Document.pdf" }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MS_Scanner")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Pair(null, null)

                try {
                    val wrote = context.contentResolver.openOutputStream(uri)?.use { out ->
                        pdfFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        true
                    } ?: false
                    if (!wrote) throw IOException("Unable to open Downloads output stream")

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                    return Pair(uri, "Downloads/MS_Scanner/$safeName")
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "MS_Scanner").apply { if (!exists()) mkdirs() }
                val targetFile = File(targetDir, safeName)
                pdfFile.copyTo(targetFile, overwrite = true)

                // Scan media for visibility in downloads
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("application/pdf"),
                    null
                )
                val uri = getFileProviderUri(context, targetFile)
                return Pair(uri, targetFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(null, null)
        }
    }

    /**
     * Copy PDF content to an output URI chosen by user via SAF (CreateDocument)
     */
    fun copyPdfToUri(context: Context, pdfFile: File, targetUri: Uri): Boolean {
        return try {
            val wrote = context.contentResolver.openOutputStream(targetUri)?.use { out ->
                pdfFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                true
            } ?: false
            wrote
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Copies an incoming content/file URI to a persistent cache file
     * and extracts its original display name.
     */
    suspend fun copyUriToLocalPdf(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            var displayName = "document_${System.currentTimeMillis()}.pdf"
            if (uri.scheme == "content") {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = it.getString(nameIndex)
                            if (!name.isNullOrBlank()) {
                                displayName = name
                            }
                        }
                    }
                }
            } else if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    displayName = File(path).name
                }
            }

            if (!displayName.endsWith(".pdf", ignoreCase = true)) {
                displayName = "$displayName.pdf"
            }
            val safeName = displayName.replace("[^a-zA-Z0-9._\\-\\s]".toRegex(), "_")

            val targetDir = File(context.cacheDir, "incoming_pdfs").apply { if (!exists()) mkdirs() }
            val targetFile = File(targetDir, "${System.currentTimeMillis()}_$safeName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 0L) {
                targetFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts each page of an existing PDF file into high-res images
     * for editing in EditSessionScreen with real-time progress and memory protection.
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
            val total = renderer.pageCount
            val pageCount = minOf(total, maxPages)

            val targetWidth = 1600 // Crisp quality for scanning and editing

            for (i in 0 until pageCount) {
                onProgress?.invoke(i + 1, pageCount)
                val page = renderer.openPage(i)
                val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                val targetHeight = (targetWidth * aspect).toInt()

                val bitmap = try {
                    Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    Bitmap.createBitmap(targetWidth / 2, targetHeight / 2, Bitmap.Config.RGB_565)
                }
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val path = ImageProcessor.saveBitmapToFile(context, bitmap, "pdf_import_p${i + 1}_")
                bitmap.recycle()
                result.add(Pair(path, path))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }
        result
    }
}
