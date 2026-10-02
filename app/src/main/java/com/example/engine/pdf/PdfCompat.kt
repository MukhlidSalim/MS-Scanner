package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Makes ANY sound PDF openable by the app's single rendering path (android.graphics.pdf.PdfRenderer, used by the
 * reader, the editor import and the signing import).
 *
 * ROOT CAUSE of "Cannot read PDF" / "the file does not display" on scanned or protected PDFs: the whole app
 * depended on PdfRenderer alone (see DocumentPipeline.importPdf and CameraViewModel.setImportedPdfPendingEdit),
 * and PdfRenderer refuses (SecurityException / IOException) files that are common for scanned documents:
 *  - "protected" PDFs with an owner password only (copiers, banks, e-government portals): they open in every
 *    reader without a password, but PdfRenderer rejects ANY encrypted file on Android < 15;
 *  - files with a damaged / non-standard cross-reference table or trailer (many scanner drivers);
 *  - PDFs whose page tree or object streams the platform PDFium build cannot parse.
 * None of this depends on text: image-only pages were never the problem, the container was.
 *
 * Strategy (each step is verified by really rendering page 1 with PdfRenderer):
 *  1. the original file;
 *  2. PdfBox repair: lenient parse (rebuilds the xref), owner-password security removed, saved as a clean copy;
 *  3. PdfBox rasterisation: every page rendered by PdfBox into a new image PDF (always readable);
 *  4. a user password is asked only when the file really requires one to be OPENED.
 * The original file is never modified.
 */
object PdfCompat {
    private const val TAG = "PdfCompat"
    private const val RASTER_DPI = 150f
    private const val MAX_RASTER_SIDE = 2400

    sealed class Result {
        /** [file] opens with PdfRenderer. [repaired] = it is a normalised copy (original kept untouched). */
        data class Ready(val file: File, val pageCount: Int, val repaired: Boolean) : Result()
        /** The file needs a password to be opened ([wrongPassword] = a given password was rejected). */
        data class NeedsPassword(val wrongPassword: Boolean) : Result()
        /** Not a PDF / truncated / empty: nothing can display it. */
        data class Unreadable(val reason: String) : Result()
    }

    /** Page count when PdfRenderer can open the file AND render its first page, otherwise null. */
    fun platformPageCount(file: File): Int? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount <= 0) return@use null
                r.openPage(0).use { p ->
                    val bmp = Bitmap.createBitmap(32, max(1, 32 * p.height / max(1, p.width)), Bitmap.Config.ARGB_8888)
                    try {
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        bmp.recycle()
                    }
                }
                r.pageCount
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "PdfRenderer cannot open ${file.name}: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    suspend fun prepare(context: Context, source: File, password: String? = null): Result = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() < 8) return@withContext Result.Unreadable("empty file")
        if (!looksLikePdf(source)) return@withContext Result.Unreadable("not a PDF")

        // 1. As is.
        if (password == null) platformPageCount(source)?.let { return@withContext Result.Ready(source, it, false) }

        val app = context.applicationContext
        runCatching { PDFBoxResourceLoader.init(app) }
        val outDir = File(app.cacheDir, "incoming_pdfs").apply { mkdirs() }
        val base = source.nameWithoutExtension

        val doc: PDDocument = try {
            if (password != null) PDDocument.load(source, password) else PDDocument.load(source)
        } catch (e: InvalidPasswordException) {
            return@withContext Result.NeedsPassword(wrongPassword = password != null)
        } catch (t: Throwable) {
            Log.w(TAG, "PdfBox cannot parse ${source.name}", t)
            return@withContext Result.Unreadable(t.javaClass.simpleName)
        }

        try {
            if (doc.numberOfPages <= 0) return@withContext Result.Unreadable("no pages")

            // 2. Repaired, unprotected copy (keeps vector text, selectable text and quality).
            val repaired = File(outDir, "${base}_ok.pdf")
            try {
                if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
                doc.save(repaired)
                platformPageCount(repaired)?.let { return@withContext Result.Ready(repaired, it, true) }
            } catch (t: Throwable) {
                Log.w(TAG, "Repair failed for ${source.name}", t)
            }
            repaired.delete()

            // 3. Image PDF rendered by PdfBox itself (always readable by PdfRenderer).
            val raster = File(outDir, "${base}_img.pdf")
            if (rasterize(doc, raster)) {
                platformPageCount(raster)?.let { return@withContext Result.Ready(raster, it, true) }
            }
            raster.delete()
            Result.Unreadable("render failed")
        } finally {
            runCatching { doc.close() }
        }
    }

    private fun looksLikePdf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(1024)
            val n = input.read(head)
            n > 4 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF")
        }
    }.getOrDefault(false)

    private fun rasterize(doc: PDDocument, out: File): Boolean {
        val renderer = PDFRenderer(doc)
        val pdf = PdfDocument()
        var written = 0
        try {
            for (i in 0 until doc.numberOfPages) {
                val box = doc.getPage(i).mediaBox
                val wPt = max(1f, box.width)
                val hPt = max(1f, box.height)
                val scale = min(RASTER_DPI / 72f, MAX_RASTER_SIDE / max(wPt, hPt))
                val bmp: Bitmap = try {
                    renderer.renderImage(i, scale)
                } catch (oom: OutOfMemoryError) {
                    renderer.renderImage(i, scale / 2f)
                } catch (t: Throwable) {
                    Log.w(TAG, "Page ${i + 1} not rendered", t)
                    null
                } ?: continue
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(wPt.toInt(), hPt.toInt(), written + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bmp, null, Rect(0, 0, wPt.toInt(), hPt.toInt()), Paint(Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                    written++
                } finally {
                    bmp.recycle()
                }
            }
            if (written == 0) return false
            FileOutputStream(out).use { pdf.writeTo(it) }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Rasterisation failed", t)
            return false
        } finally {
            pdf.close()
        }
    }
}
