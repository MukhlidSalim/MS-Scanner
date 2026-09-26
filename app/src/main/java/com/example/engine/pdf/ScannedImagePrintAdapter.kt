package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream

/**
 * PrintDocumentAdapter implementation that integrates with the Android Print framework.
 * Enables generating PDF files directly via the system "Save as PDF" print service
 * and sending scanned documents to connected physical or network printers.
 */
class ScannedImagePrintAdapter(
    private val context: Context,
    private val documentTitle: String,
    private val imagePaths: List<String>
) : PrintDocumentAdapter() {

    private var printedPdfDocument: PrintedPdfDocument? = null

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

        printedPdfDocument = PrintedPdfDocument(context, newAttributes)

        val safeDocName = documentTitle.replace("[^a-zA-Z0-9_\\-\\s]".toRegex(), "_").trim().ifBlank { "Document" }
        val info = PrintDocumentInfo.Builder("${safeDocName}.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(imagePaths.size)
            .build()

        val changed = newAttributes != oldAttributes
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val pdfDoc = printedPdfDocument ?: run {
            callback.onWriteFailed("Print document not ready")
            return
        }

        try {
            val totalPages = imagePaths.size
            for (i in 0 until totalPages) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }

                // Check if page i is requested
                val isRequested = pageRanges.any { range -> i in range.start..range.end }
                if (!isRequested) continue

                val path = imagePaths[i]
                val bitmap = runBlocking(Dispatchers.IO) {
                    ImageProcessor.loadBitmapFromFile(path, maxDim = 2000)
                }

                val page = pdfDoc.startPage(i)
                val canvas = page.canvas

                if (bitmap != null) {
                    val pageWidth = canvas.width
                    val pageHeight = canvas.height
                    val margin = 20
                    val usableW = (pageWidth - margin * 2).coerceAtLeast(1)
                    val usableH = (pageHeight - margin * 2).coerceAtLeast(1)

                    val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                    val pageAspect = usableW.toFloat() / usableH.toFloat()

                    val destRect = if (bmpAspect > pageAspect) {
                        val drawW = usableW
                        val drawH = (usableW / bmpAspect).toInt()
                        val offsetY = margin + (usableH - drawH) / 2
                        Rect(margin, offsetY, margin + drawW, offsetY + drawH)
                    } else {
                        val drawH = usableH
                        val drawW = (usableH * bmpAspect).toInt()
                        val offsetX = margin + (usableW - drawW) / 2
                        Rect(offsetX, margin, offsetX + drawW, margin + drawH)
                    }

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, null, destRect, paint)

                    // Draw page number footer in print document
                    val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 10f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("${i + 1} / $totalPages", (pageWidth / 2).toFloat(), (pageHeight - 6).toFloat(), numPaint)

                    bitmap.recycle()
                }

                pdfDoc.finishPage(page)
            }

            FileOutputStream(destination.fileDescriptor).use { out ->
                pdfDoc.writeTo(out)
            }

            callback.onWriteFinished(pageRanges)
        } catch (e: Exception) {
            e.printStackTrace()
            callback.onWriteFailed(e.localizedMessage ?: "Print writing failed")
        } finally {
            pdfDoc.close()
            printedPdfDocument = null
        }
    }
}
