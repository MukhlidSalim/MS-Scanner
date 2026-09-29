package com.example.engine.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
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
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * PrintDocumentAdapter for scanned pages (printers and the system "Save as PDF").
 *
 * onWrite() is called on the main thread; pages are now rendered on a background coroutine and the
 * callback is delivered on the main thread (previously every page was decoded with runBlocking on the
 * main thread -> ANR on multi-page documents). Page ranges are honoured and reported correctly.
 */
class ScannedImagePrintAdapter(
    private val context: Context,
    private val documentTitle: String,
    private val imagePaths: List<String>
) : PrintDocumentAdapter() {

    private var attributes: PrintAttributes? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writeJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (imagePaths.isEmpty()) {
            callback.onLayoutFailed("No pages to print")
            return
        }
        attributes = newAttributes
        val info = PrintDocumentInfo.Builder("${PdfEngine.safeFileName(documentTitle)}.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(imagePaths.size)
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
            val pdfDoc = PrintedPdfDocument(context, attrs)
            val written = mutableListOf<Int>()
            try {
                val totalPages = imagePaths.size
                for (i in 0 until totalPages) {
                    if (!isActive || cancellationSignal?.isCanceled == true) {
                        mainHandler.post { callback.onWriteCancelled() }
                        return@launch
                    }
                    if (pageRanges.none { i in it.start..it.end }) continue
                    val bitmap = ImageProcessor.loadBitmapFromFile(imagePaths[i], maxDim = 2000)
                    val page = pdfDoc.startPage(i)
                    try {
                        val canvas = page.canvas
                        if (bitmap != null) {
                            val pageWidth = canvas.width
                            val pageHeight = canvas.height
                            val margin = 12
                            val usableW = (pageWidth - margin * 2).coerceAtLeast(1)
                            val usableH = (pageHeight - margin * 2).coerceAtLeast(1)
                            val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                            val pageAspect = usableW.toFloat() / usableH.toFloat()
                            val destRect = if (bmpAspect > pageAspect) {
                                val drawH = (usableW / bmpAspect).toInt()
                                val offsetY = margin + (usableH - drawH) / 2
                                Rect(margin, offsetY, margin + usableW, offsetY + drawH)
                            } else {
                                val drawW = (usableH * bmpAspect).toInt()
                                val offsetX = margin + (usableW - drawW) / 2
                                Rect(offsetX, margin, offsetX + drawW, margin + usableH)
                            }
                            canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        }
                    } finally {
                        bitmap?.recycle()
                        pdfDoc.finishPage(page)
                    }
                    written += i
                }
                FileOutputStream(destination.fileDescriptor).use { out -> pdfDoc.writeTo(out) }
                val ranges = toRanges(written)
                mainHandler.post { callback.onWriteFinished(ranges) }
            } catch (e: Throwable) {
                e.printStackTrace()
                mainHandler.post { callback.onWriteFailed(e.localizedMessage ?: "Print writing failed") }
            } finally {
                pdfDoc.close()
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
