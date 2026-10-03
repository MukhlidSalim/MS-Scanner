package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Prints an already generated PDF file. Each page is rasterized with [PdfRenderer] at the printer page
 * size (2x for sharpness) on a background coroutine; callbacks are delivered on the main thread.
 * Fixes the viewer's Print button, which opened an external app instead of printing.
 */
class PdfFilePrintAdapter(
    private val context: Context,
    private val documentTitle: String,
    private val pdfFile: File
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null
    private var pageCount = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writeJob: Job? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        metadata: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        pageCount = runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)
        if (pageCount == 0) {
            callback.onLayoutFailed("Could not read the PDF")
            return
        }
        attributes = newAttributes
        val info = PrintDocumentInfo.Builder("${PdfEngine.safeFileName(documentTitle)}.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val attrs = attributes ?: run {
            callback.onWriteFailed("Print document not ready")
            return
        }
        writeJob?.cancel()
        val job = scope.launch {
            val out = PrintedPdfDocument(context, attrs)
            val written = mutableListOf<Int>()
            try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (i in 0 until pageCount) {
                            if (!isActive || cancellationSignal?.isCanceled == true) {
                                main.post { callback.onWriteCancelled() }
                                return@launch
                            }
                            if (pageRanges.none { (it == PageRange.ALL_PAGES) || i in it.start..it.end }) continue
                            val src = renderer.openPage(i)
                            val page = out.startPage(i)
                            try {
                                val canvas = page.canvas
                                val bmp = Bitmap.createBitmap(canvas.width * 2, canvas.height * 2, Bitmap.Config.ARGB_8888)
                                try {
                                    bmp.eraseColor(Color.WHITE)
                                    src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)
                                } finally {
                                    bmp.recycle()
                                }
                            } finally {
                                src.close()
                                out.finishPage(page)
                            }
                            written += i
                        }
                    }
                }
                FileOutputStream(destination.fileDescriptor).use { out.writeTo(it) }
                val ranges = toRanges(written)
                main.post { callback.onWriteFinished(ranges) }
            } catch (e: Throwable) {
                e.printStackTrace()
                main.post { callback.onWriteFailed(e.localizedMessage ?: "Print writing failed") }
            } finally {
                out.close()
            }
        }
        writeJob = job
        cancellationSignal?.setOnCancelListener { job.cancel() }
    }

    override fun onFinish() {
        super.onFinish()
        scope.cancel()
    }

    private fun toRanges(pages: List<Int>): Array<PageRange> {
        if (pages.isEmpty()) return arrayOf(PageRange.ALL_PAGES)
        val result = mutableListOf<PageRange>()
        var start = pages.first()
        var prev = start
        for (p in pages.drop(1)) {
            if (p != prev + 1) {
                result += PageRange(start, prev)
                start = p
            }
            prev = p
        }
        result += PageRange(start, prev)
        return result.toTypedArray()
    }
}
