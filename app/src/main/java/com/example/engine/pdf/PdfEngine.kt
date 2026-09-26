package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
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
    val includeSearchableText: Boolean = true
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

        try {
            for (i in pagePathsAndOcr.indices) {
                val (path, _) = pagePathsAndOcr[i]
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

                pdfDocument.finishPage(page)

                // Immediately recycle to prevent OutOfMemory on multi-page batches
                if (originalBitmap != renderBitmap) {
                    renderBitmap.recycle()
                }
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
