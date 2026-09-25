package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.engine.cv.ImageProcessor
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
    val includeSearchableText: Boolean = true
)

object PdfEngine {

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

        try {
            for (i in pagePathsAndOcr.indices) {
                val (path, ocrText) = pagePathsAndOcr[i]
                val originalBitmap = ImageProcessor.loadBitmapFromFile(path, maxDim = 2400) ?: continue

                // Determine target PDF page dimensions in points (72 pt per inch)
                val (pageWidth, pageHeight) = when (config.pageSize) {
                    PageSizePreset.A4 -> Pair(595, 842)
                    PageSizePreset.LETTER -> Pair(612, 792)
                    PageSizePreset.LEGAL -> Pair(612, 1008)
                    PageSizePreset.FIT_ORIGINAL -> {
                        val maxPt = 800
                        val aspect = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                        if (aspect > 1f) Pair(maxPt, (maxPt / aspect).toInt())
                        else Pair((maxPt * aspect).toInt(), maxPt)
                    }
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Compress bitmap if needed based on preset
                val renderBitmap = if (config.compression != CompressionPreset.MAXIMUM) {
                    val quality = config.compression.qualityPercent
                    val stream = java.io.ByteArrayOutputStream()
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    val bytes = stream.toByteArray()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    originalBitmap
                }

                // Fit bitmap centered inside the page
                val bitmapAspect = renderBitmap.width.toFloat() / renderBitmap.height.toFloat()
                val pageAspect = pageWidth.toFloat() / pageHeight.toFloat()

                val drawRect = if (bitmapAspect > pageAspect) {
                    val drawW = pageWidth
                    val drawH = (pageWidth / bitmapAspect).toInt()
                    val offsetY = (pageHeight - drawH) / 2
                    Rect(0, offsetY, drawW, offsetY + drawH)
                } else {
                    val drawH = pageHeight
                    val drawW = (pageHeight * bitmapAspect).toInt()
                    val offsetX = (pageWidth - drawW) / 2
                    Rect(offsetX, 0, offsetX + drawW, drawH)
                }

                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(renderBitmap, null, drawRect, paint)

                // Searchable text layer (invisible text on canvas for searchability in PDF readers)
                if (config.includeSearchableText && ocrText.isNotBlank()) {
                    val textPaint = Paint().apply {
                        color = Color.TRANSPARENT
                        textSize = 8f
                    }
                    val lines = ocrText.lines().take(40)
                    var textY = 20f
                    for (line in lines) {
                        if (line.isNotBlank()) {
                            canvas.drawText(line.take(80), 20f, textY, textPaint)
                            textY += 12f
                        }
                    }
                }

                pdfDocument.finishPage(page)
                
                // Free memory
                if (originalBitmap != renderBitmap) renderBitmap.recycle()
                originalBitmap.recycle()
            }

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
        } finally {
            pdfDocument.close()
        }

        outputFile
    }
}
