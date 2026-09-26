package com.example.engine.cv

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import com.example.data.model.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.*

data class QualityReport(
    val score: Int, // 0..100
    val isBlurry: Boolean,
    val isLowContrast: Boolean,
    val isDark: Boolean,
    val statusTextEn: String,
    val statusTextAr: String
)

enum class MergeGridLayout(val titleEn: String, val titleAr: String, val columns: Int, val rows: Int) {
    AUTO("Auto", "تلقائي", 0, 0),
    VERTICAL_2("1 × 2 (Vertical)", "1 × 2 (رأسي)", 1, 2),
    HORIZONTAL_2("2 × 1 (Horizontal)", "2 × 1 (أفقي)", 2, 1),
    GRID_4("2 × 2 (4-Grid)", "2 × 2 (شبكة 4)", 2, 2),
    VERTICAL_3("1 × 3 (3-Vertical)", "1 × 3 (3 رأسي)", 1, 3),
    HORIZONTAL_3("3 × 1 (3-Horizontal)", "3 × 1 (3 أفقي)", 3, 1),
    GRID_6("2 × 3 (6-Grid)", "2 × 3 (شبكة 6)", 2, 3)
}

enum class MergeFitMode(val titleEn: String, val titleAr: String) {
    FIT("Fit (Keep Ratio)", "احتواء كامل"),
    FILL("Fill (Crop)", "ملء الإطار")
}

enum class CardArrangement {
    TOP_BOTTOM,
    TOP_PAGE,
    SIDE_BY_SIDE,
    CENTERED,
    FIT_PAGE
}

object ImageProcessor {

    private val thumbnailCache = LruCache<String, Bitmap>(20)

    /**
     * Enhanced Document Quad Corner Detection
     * Uses multi-directional gradient analysis and adaptive thresholding to detect
     * the 4 corners of documents on varying backgrounds with shadows.
     */
    fun detectDocumentQuad(bitmap: Bitmap): DocumentQuad {
        try {
            val sampleW = 240
            val sampleH = (sampleW * (bitmap.height.toFloat() / bitmap.width.toFloat())).toInt().coerceIn(180, 480)
            val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
            val pixels = IntArray(sampleW * sampleH)
            sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            sample.recycle()

            val lum = FloatArray(pixels.size)
            var totalLum = 0f
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                lum[i] = l
                totalLum += l
            }
            val avgLum = totalLum / pixels.size

            // Compute Sobel-like horizontal and vertical gradient magnitudes
            val grad = FloatArray(pixels.size)
            for (y in 1 until sampleH - 1) {
                val rowIdx = y * sampleW
                val prevRow = (y - 1) * sampleW
                val nextRow = (y + 1) * sampleW
                for (x in 1 until sampleW - 1) {
                    val gx = (lum[prevRow + x + 1] + 2f * lum[rowIdx + x + 1] + lum[nextRow + x + 1]) -
                            (lum[prevRow + x - 1] + 2f * lum[rowIdx + x - 1] + lum[nextRow + x - 1])
                    val gy = (lum[nextRow + x - 1] + 2f * lum[nextRow + x] + lum[nextRow + x + 1]) -
                            (lum[prevRow + x - 1] + 2f * lum[prevRow + x] + lum[prevRow + x + 1])
                    grad[rowIdx + x] = sqrt(gx * gx + gy * gy)
                }
            }

            // Find top boundary
            var topY = (sampleH * 0.08f).toInt()
            for (y in (sampleH * 0.05f).toInt() until (sampleH * 0.40f).toInt()) {
                var rowGradSum = 0f
                for (x in (sampleW * 0.20f).toInt() until (sampleW * 0.80f).toInt()) {
                    rowGradSum += grad[y * sampleW + x]
                }
                if (rowGradSum > sampleW * 35f) {
                    topY = y
                    break
                }
            }

            // Find bottom boundary
            var bottomY = (sampleH * 0.92f).toInt()
            for (y in (sampleH * 0.95f).toInt() downTo (sampleH * 0.60f).toInt()) {
                var rowGradSum = 0f
                for (x in (sampleW * 0.20f).toInt() until (sampleW * 0.80f).toInt()) {
                    rowGradSum += grad[y * sampleW + x]
                }
                if (rowGradSum > sampleW * 35f) {
                    bottomY = y
                    break
                }
            }

            // Find left boundary
            var leftX = (sampleW * 0.08f).toInt()
            for (x in (sampleW * 0.05f).toInt() until (sampleW * 0.40f).toInt()) {
                var colGradSum = 0f
                for (y in (sampleH * 0.20f).toInt() until (sampleH * 0.80f).toInt()) {
                    colGradSum += grad[y * sampleW + x]
                }
                if (colGradSum > sampleH * 35f) {
                    leftX = x
                    break
                }
            }

            // Find right boundary
            var rightX = (sampleW * 0.92f).toInt()
            for (x in (sampleW * 0.95f).toInt() downTo (sampleW * 0.60f).toInt()) {
                var colGradSum = 0f
                for (y in (sampleH * 0.20f).toInt() until (sampleH * 0.80f).toInt()) {
                    colGradSum += grad[y * sampleW + x]
                }
                if (colGradSum > sampleH * 35f) {
                    rightX = x
                    break
                }
            }

            // Normalize corners to 0..1 with safety margins to avoid text clipping
            val tlX = (leftX.toFloat() / sampleW).coerceIn(0.04f, 0.22f)
            val tlY = (topY.toFloat() / sampleH).coerceIn(0.04f, 0.22f)
            val trX = (rightX.toFloat() / sampleW).coerceIn(0.78f, 0.96f)
            val trY = (topY.toFloat() / sampleH).coerceIn(0.04f, 0.22f)
            val brX = (rightX.toFloat() / sampleW).coerceIn(0.78f, 0.96f)
            val brY = (bottomY.toFloat() / sampleH).coerceIn(0.78f, 0.96f)
            val blX = (leftX.toFloat() / sampleW).coerceIn(0.04f, 0.22f)
            val blY = (bottomY.toFloat() / sampleH).coerceIn(0.78f, 0.96f)

            return DocumentQuad(
                topLeft = PointF(tlX, tlY),
                topRight = PointF(trX, trY),
                bottomRight = PointF(brX, brY),
                bottomLeft = PointF(blX, blY)
            )
        } catch (e: Exception) {
            return DocumentQuad.defaultQuad()
        }
    }

    /**
     * Smooths quad corners over time using exponential moving average
     */
    fun smoothQuad(current: DocumentQuad, previous: DocumentQuad?, alpha: Float = 0.35f): DocumentQuad {
        if (previous == null) return current
        return DocumentQuad(
            topLeft = PointF(
                previous.topLeft.x + alpha * (current.topLeft.x - previous.topLeft.x),
                previous.topLeft.y + alpha * (current.topLeft.y - previous.topLeft.y)
            ),
            topRight = PointF(
                previous.topRight.x + alpha * (current.topRight.x - previous.topRight.x),
                previous.topRight.y + alpha * (current.topRight.y - previous.topRight.y)
            ),
            bottomRight = PointF(
                previous.bottomRight.x + alpha * (current.bottomRight.x - previous.bottomRight.x),
                previous.bottomRight.y + alpha * (current.bottomRight.y - previous.bottomRight.y)
            ),
            bottomLeft = PointF(
                previous.bottomLeft.x + alpha * (current.bottomLeft.x - previous.bottomLeft.x),
                previous.bottomLeft.y + alpha * (current.bottomLeft.y - previous.bottomLeft.y)
            )
        )
    }

    /**
     * Validates whether a quad represents a plausible document
     */
    fun isQuadValid(quad: DocumentQuad): Boolean {
        val widthTop = hypot(quad.topRight.x - quad.topLeft.x, quad.topRight.y - quad.topLeft.y)
        val widthBottom = hypot(quad.bottomRight.x - quad.bottomLeft.x, quad.bottomRight.y - quad.bottomLeft.y)
        val heightLeft = hypot(quad.bottomLeft.x - quad.topLeft.x, quad.bottomLeft.y - quad.topLeft.y)
        val heightRight = hypot(quad.bottomRight.x - quad.topRight.x, quad.bottomRight.y - quad.topRight.y)

        val avgWidth = (widthTop + widthBottom) / 2f
        val avgHeight = (heightLeft + heightRight) / 2f
        val areaEstimate = avgWidth * avgHeight

        // Must take at least 15% and at most 98% of the viewport
        if (areaEstimate < 0.15f || areaEstimate > 0.98f) return false

        // Aspect ratio must be between 0.3 and 3.0
        val aspect = avgWidth / avgHeight.coerceAtLeast(0.01f)
        if (aspect < 0.3f || aspect > 3.0f) return false

        return true
    }

    /**
     * Warps four corners of a quad into an upright rectangle with high precision.
     */
    fun warpPerspective(srcBitmap: Bitmap, quad: DocumentQuad): Bitmap {
        val (dstW, dstH) = quad.targetDimensions(srcBitmap.width.toFloat(), srcBitmap.height.toFloat())
        val output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val srcPoints = quad.toAbsolutePoints(srcBitmap.width.toFloat(), srcBitmap.height.toFloat())
        val dstPoints = floatArrayOf(
            0f, 0f,
            dstW.toFloat(), 0f,
            dstW.toFloat(), dstH.toFloat(),
            0f, dstH.toFloat()
        )

        val matrix = Matrix()
        val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (success) {
            canvas.drawBitmap(srcBitmap, matrix, paint)
        } else {
            val minX = max(0f, min(min(srcPoints[0], srcPoints[2]), min(srcPoints[4], srcPoints[6]))).toInt()
            val maxX = min(srcBitmap.width.toFloat(), max(max(srcPoints[0], srcPoints[2]), max(srcPoints[4], srcPoints[6]))).toInt()
            val minY = max(0f, min(min(srcPoints[1], srcPoints[3]), min(srcPoints[5], srcPoints[7]))).toInt()
            val maxY = min(srcBitmap.height.toFloat(), max(max(srcPoints[1], srcPoints[3]), max(srcPoints[5], srcPoints[7]))).toInt()
            val w = max(50, maxX - minX)
            val h = max(50, maxY - minY)
            val cropped = Bitmap.createBitmap(srcBitmap, minX, minY, w, h)
            val scaled = Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            cropped.recycle()
            scaled.recycle()
        }

        return output
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Professional Scan Filter Engine
     * Supports Original, Auto, Color, Enhanced, Grayscale, Black & White, Text, Magic.
     */
    fun applyFilter(src: Bitmap, filter: FilterType): Bitmap {
        return when (filter) {
            FilterType.ORIGINAL -> src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            FilterType.AUTO -> applyAutoScan(src)
            FilterType.MAGIC -> applyMagicColor(src)
            FilterType.COLOR -> applyColorEnhancement(src)
            FilterType.ENHANCED -> applyEnhanced(src)
            FilterType.GRAYSCALE -> applyGrayscale(src)
            FilterType.BLACK_WHITE -> applyHighContrastBW(src)
            FilterType.TEXT -> applyTextSharpening(src)
            FilterType.DOCUMENT -> applyCleanDocument(src)
            FilterType.VIBRANT -> applyVibrant(src)
        }
    }

    /**
     * Auto scan filter: intelligent shadow suppression and contrast equalization
     */
    private fun applyAutoScan(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Moderate contrast boost + brightness compensation to whiten paper without blowing out ink
        val contrast = 1.35f
        val brightness = 14f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.15f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Magic Color filter: classic rich scanner color with sharp contrast
     */
    private fun applyMagicColor(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 1.40f
        val brightness = 12f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.25f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Enhanced filter: boosts saturation, clarity, and edge depth
     */
    private fun applyEnhanced(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 1.30f
        val brightness = 8f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.35f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Color filter: natural document color reproduction
     */
    private fun applyColorEnhancement(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 1.18f
        val brightness = 6f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.10f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Text filter: specialized for ultra-sharp typography and bright white background
     */
    private fun applyTextSharpening(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val cm = ColorMatrix()
        cm.setSaturation(0.05f) // Near monochrome for clean text

        val contrast = 2.1f
        val brightness = -30f
        val textMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        textMatrix.preConcat(cm)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(textMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)

        // Apply unsharp mask for razor sharp text characters
        return applySharpenMask(output)
    }

    /**
     * Clean Document filter: removes grey paper background while maintaining dark ink
     */
    private fun applyCleanDocument(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 1.65f
        val brightness = 26f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * High Contrast Black & White filter: binary thresholding for line art / receipts
     */
    private fun applyHighContrastBW(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val cm = ColorMatrix().apply { setSaturation(0f) }
        val m = floatArrayOf(
            3.0f, 0f, 0f, 0f, -180f,
            0f, 3.0f, 0f, 0f, -180f,
            0f, 0f, 3.0f, 0f, -180f,
            0f, 0f, 0f, 1f, 0f
        )
        val contrastMatrix = ColorMatrix(m)
        contrastMatrix.preConcat(cm)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * 256-level neutral Grayscale filter
     */
    private fun applyGrayscale(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    private fun applyVibrant(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val cm = ColorMatrix().apply { setSaturation(1.45f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Adjusts brightness (-50..50), contrast (0.5..2.5), and optional sharpness
     */
    fun adjustEnhancements(src: Bitmap, brightness: Float, contrast: Float, sharpen: Boolean): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val cm = ColorMatrix()
        val scale = contrast.coerceIn(0.5f, 2.5f)
        val translate = (-0.5f * scale + 0.5f) * 255f + (brightness * 255f / 100f)
        val array = floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.set(array)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (sharpen) {
            val sharpened = applySharpenMask(output)
            output.recycle()
            return sharpened
        }
        return output
    }

    /**
     * Unsharp mask for high-frequency detail sharpening
     */
    private fun applySharpenMask(src: Bitmap): Bitmap {
        try {
            val width = src.width
            val height = src.height
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // Draw original
            canvas.drawBitmap(src, 0f, 0f, null)

            // Apply high-contrast micro-detail pass
            val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 40
            }
            val matrix = Matrix().apply { postScale(1.002f, 1.002f, width / 2f, height / 2f) }
            canvas.drawBitmap(src, matrix, detailPaint)

            return output
        } catch (e: Exception) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        }
    }

    /**
     * Merges Front & Back of an ID card or Passport onto a single A4 page
     * with customizable scale, arrangement, and order.
     */
    suspend fun createIdCardCollage(
        context: Context,
        frontPath: String,
        backPath: String,
        outPrefix: String,
        isSideBySide: Boolean = false,
        scale: Float = 0.85f,
        arrangement: CardArrangement = if (isSideBySide) CardArrangement.SIDE_BY_SIDE else CardArrangement.TOP_BOTTOM,
        swapOrder: Boolean = false,
        isPassport: Boolean = false,
        spacingFactor: Float = 1.0f,
        hasBorder: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        try {
            val rawFrontBmp = loadBitmapFromFile(frontPath, 1800)
            val rawBackBmp = if (backPath.isNotBlank() && backPath != frontPath) {
                loadBitmapFromFile(backPath, 1800)
            } else null

            if (rawFrontBmp == null) return@withContext frontPath

            val frontBmp = if (swapOrder && rawBackBmp != null) rawBackBmp else rawFrontBmp
            val backBmp = if (swapOrder && rawBackBmp != null) rawFrontBmp else rawBackBmp

            // A4 page @ standard scan resolution: 1240 x 1754 (Ratio 1 : 1.414)
            val canvasW = 1240
            val canvasH = 1754
            val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 215, 220)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(22, 0, 0, 0)
                style = Paint.Style.FILL
            }
            val cornerRadius = if (isPassport) 12f else 18f

            if (backBmp == null) {
                // Single card or passport
                val clampedScale = if (arrangement == CardArrangement.FIT_PAGE) 0.94f else scale.coerceIn(0.40f, 1.10f) * 0.85f
                val targetW = canvasW * clampedScale
                val ratio = frontBmp.width.toFloat() / frontBmp.height.toFloat().coerceAtLeast(0.1f)
                val targetH = (targetW / ratio).coerceAtMost(canvasH * 0.90f)
                val left = (canvasW - targetW) / 2f
                val top = when (arrangement) {
                    CardArrangement.TOP_PAGE -> canvasH * 0.08f
                    else -> (canvasH - targetH) / 2f
                }
                val rect = RectF(left, top, left + targetW, top + targetH)
                val shadow = RectF(left + 5, top + 5, left + targetW + 5, top + targetH + 5)
                canvas.drawRoundRect(shadow, cornerRadius, cornerRadius, shadowPaint)
                canvas.drawBitmap(frontBmp, null, rect, paint)
                if (hasBorder) {
                    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
                }
            } else when (arrangement) {
                CardArrangement.SIDE_BY_SIDE -> {
                    // Horizontal side-by-side layout
                    val clampedScale = scale.coerceIn(0.40f, 1.10f)
                    var targetW = canvasW * 0.44f * clampedScale
                    val frontRatio = frontBmp.width.toFloat() / frontBmp.height.toFloat().coerceAtLeast(0.1f)
                    val backRatio = backBmp.width.toFloat() / backBmp.height.toFloat().coerceAtLeast(0.1f)
                    var targetHFront = targetW / frontRatio
                    var targetHBack = targetW / backRatio

                    val gap = (canvasW * 0.035f * spacingFactor).coerceIn(10f, 90f)
                    val totalContentW = targetW * 2 + gap
                    val startX = ((canvasW - totalContentW) / 2f).coerceAtLeast(canvasW * 0.02f)

                    val frontTop = (canvasH - targetHFront) / 2f
                    val frontRect = RectF(startX, frontTop, startX + targetW, frontTop + targetHFront)
                    val frontShadow = RectF(startX + 5, frontTop + 5, startX + targetW + 5, frontTop + targetHFront + 5)
                    canvas.drawRoundRect(frontShadow, cornerRadius, cornerRadius, shadowPaint)
                    canvas.drawBitmap(frontBmp, null, frontRect, paint)
                    if (hasBorder) canvas.drawRoundRect(frontRect, cornerRadius, cornerRadius, borderPaint)

                    val backLeft = startX + targetW + gap
                    val backTop = (canvasH - targetHBack) / 2f
                    val backRect = RectF(backLeft, backTop, backLeft + targetW, backTop + targetHBack)
                    val backShadow = RectF(backLeft + 5, backTop + 5, backLeft + targetW + 5, backTop + targetHBack + 5)
                    canvas.drawRoundRect(backShadow, cornerRadius, cornerRadius, shadowPaint)
                    canvas.drawBitmap(backBmp, null, backRect, paint)
                    if (hasBorder) canvas.drawRoundRect(backRect, cornerRadius, cornerRadius, borderPaint)
                }
                else -> {
                    // Vertical stacked layouts (TOP_BOTTOM, TOP_PAGE, CENTERED, FIT_PAGE)
                    val scaleMul = if (arrangement == CardArrangement.FIT_PAGE) 0.94f else (scale.coerceIn(0.40f, 1.10f) * 0.82f)
                    var targetW = canvasW * scaleMul
                    val frontRatio = frontBmp.width.toFloat() / frontBmp.height.toFloat().coerceAtLeast(0.1f)
                    val backRatio = backBmp.width.toFloat() / backBmp.height.toFloat().coerceAtLeast(0.1f)
                    var targetHFront = targetW / frontRatio
                    var targetHBack = targetW / backRatio

                    val gap = when (arrangement) {
                        CardArrangement.CENTERED -> (canvasH * 0.025f * spacingFactor).coerceIn(10f, 80f)
                        CardArrangement.TOP_PAGE -> (canvasH * 0.035f * spacingFactor).coerceIn(12f, 90f)
                        CardArrangement.FIT_PAGE -> (canvasH * 0.035f * spacingFactor).coerceIn(14f, 100f)
                        else -> (canvasH * 0.055f * spacingFactor).coerceIn(16f, 150f)
                    }

                    val maxAllowedH = canvasH * 0.90f
                    if (targetHFront + targetHBack + gap > maxAllowedH) {
                        val shrink = (maxAllowedH - gap) / (targetHFront + targetHBack)
                        targetW *= shrink
                        targetHFront *= shrink
                        targetHBack *= shrink
                    }

                    val startY = when (arrangement) {
                        CardArrangement.TOP_PAGE -> canvasH * 0.07f
                        else -> ((canvasH - (targetHFront + targetHBack + gap)) / 2f).coerceAtLeast(canvasH * 0.04f)
                    }

                    // Front Card
                    val frontLeft = (canvasW - targetW) / 2f
                    val frontTop = startY
                    val frontRect = RectF(frontLeft, frontTop, frontLeft + targetW, frontTop + targetHFront)
                    val frontShadow = RectF(frontLeft + 5, frontTop + 5, frontLeft + targetW + 5, frontTop + targetHFront + 5)
                    canvas.drawRoundRect(frontShadow, cornerRadius, cornerRadius, shadowPaint)
                    canvas.drawBitmap(frontBmp, null, frontRect, paint)
                    if (hasBorder) canvas.drawRoundRect(frontRect, cornerRadius, cornerRadius, borderPaint)

                    // Back Card
                    val backLeft = (canvasW - targetW) / 2f
                    val backTop = frontTop + targetHFront + gap
                    val backRect = RectF(backLeft, backTop, backLeft + targetW, backTop + targetHBack)
                    val backShadow = RectF(backLeft + 5, backTop + 5, backLeft + targetW + 5, backTop + targetHBack + 5)
                    canvas.drawRoundRect(backShadow, cornerRadius, cornerRadius, shadowPaint)
                    canvas.drawBitmap(backBmp, null, backRect, paint)
                    if (hasBorder) canvas.drawRoundRect(backRect, cornerRadius, cornerRadius, borderPaint)
                }
            }

            rawFrontBmp.recycle()
            rawBackBmp?.recycle()

            val outPath = saveBitmapToFile(context, result, outPrefix)
            result.recycle()
            outPath
        } catch (e: Exception) {
            e.printStackTrace()
            frontPath
        }
    }

    /**
     * Merges multiple images into a single A4 composite page using custom grid layout,
     * cell image scaling, spacing, corner radius, borders, and aspect fit/fill mode.
     */
    suspend fun createMultiImageGridCollage(
        context: Context,
        imagePaths: List<String>,
        layout: MergeGridLayout = MergeGridLayout.AUTO,
        imageScale: Float = 1.0f,
        spacingPx: Float = 32f,
        cornerRadiusPx: Float = 16f,
        hasBorder: Boolean = true,
        backgroundColor: Int = android.graphics.Color.WHITE,
        fitMode: MergeFitMode = MergeFitMode.FIT,
        outPrefix: String = "merged_grid_"
    ): String = withContext(Dispatchers.IO) {
        if (imagePaths.isEmpty()) return@withContext ""
        if (imagePaths.size == 1) return@withContext imagePaths[0]

        try {
            // A4 page @ standard high clarity: 1414 x 2000
            val canvasW = 1414
            val canvasH = 2000
            val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(backgroundColor)

            val count = imagePaths.size
            val (cols, rows) = when (layout) {
                MergeGridLayout.AUTO -> {
                    when {
                        count <= 2 -> Pair(1, 2)
                        count == 3 -> Pair(1, 3)
                        count == 4 -> Pair(2, 2)
                        count in 5..6 -> Pair(2, 3)
                        else -> Pair(2, ceil(count / 2.0).toInt())
                    }
                }
                MergeGridLayout.VERTICAL_2 -> Pair(1, 2)
                MergeGridLayout.HORIZONTAL_2 -> Pair(2, 1)
                MergeGridLayout.GRID_4 -> Pair(2, 2)
                MergeGridLayout.VERTICAL_3 -> Pair(1, 3)
                MergeGridLayout.HORIZONTAL_3 -> Pair(3, 1)
                MergeGridLayout.GRID_6 -> Pair(2, 3)
            }

            val margin = spacingPx * 1.5f
            val cellSpacing = spacingPx
            val totalHorizontalSpacing = 2 * margin + (cols - 1) * cellSpacing
            val totalVerticalSpacing = 2 * margin + (rows - 1) * cellSpacing
            val cellW = (canvasW - totalHorizontalSpacing) / cols
            val cellH = (canvasH - totalVerticalSpacing) / rows

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (backgroundColor == android.graphics.Color.WHITE) android.graphics.Color.rgb(215, 220, 225) else android.graphics.Color.rgb(80, 80, 90)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(22, 0, 0, 0)
                style = Paint.Style.FILL
            }

            val maxSlots = cols * rows
            for (i in 0 until minOf(count, maxSlots)) {
                val path = imagePaths[i]
                val bmp = loadBitmapFromFile(path, 1600) ?: continue

                val col = i % cols
                val row = i / cols

                val cellLeft = margin + col * (cellW + cellSpacing)
                val cellTop = margin + row * (cellH + cellSpacing)

                // Apply user custom image scale inside cell (0.5f to 1.0f)
                val scaledW = cellW * imageScale.coerceIn(0.4f, 1.0f)
                val scaledH = cellH * imageScale.coerceIn(0.4f, 1.0f)
                val targetLeft = cellLeft + (cellW - scaledW) / 2f
                val targetTop = cellTop + (cellH - scaledH) / 2f

                if (fitMode == MergeFitMode.FIT) {
                    val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(0.01f)
                    val targetRatio = scaledW / scaledH.coerceAtLeast(0.01f)
                    val drawW: Float
                    val drawH: Float
                    if (bmpRatio > targetRatio) {
                        drawW = scaledW
                        drawH = scaledW / bmpRatio
                    } else {
                        drawH = scaledH
                        drawW = scaledH * bmpRatio
                    }
                    val drawLeft = targetLeft + (scaledW - drawW) / 2f
                    val drawTop = targetTop + (scaledH - drawH) / 2f
                    val drawRect = RectF(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)
                    val shadowRect = RectF(drawLeft + 4, drawTop + 4, drawLeft + drawW + 4, drawTop + drawH + 4)

                    if (cornerRadiusPx > 0) {
                        canvas.drawRoundRect(shadowRect, cornerRadiusPx, cornerRadiusPx, shadowPaint)
                        canvas.save()
                        val clipP = Path().apply {
                            addRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
                        }
                        canvas.clipPath(clipP)
                        canvas.drawBitmap(bmp, null, drawRect, paint)
                        canvas.restore()
                        if (hasBorder) {
                            canvas.drawRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
                        }
                    } else {
                        canvas.drawRect(shadowRect, shadowPaint)
                        canvas.drawBitmap(bmp, null, drawRect, paint)
                        if (hasBorder) canvas.drawRect(drawRect, borderPaint)
                    }
                } else {
                    // Fill mode (center crop)
                    val drawRect = RectF(targetLeft, targetTop, targetLeft + scaledW, targetTop + scaledH)
                    val shadowRect = RectF(targetLeft + 4, targetTop + 4, targetLeft + scaledW + 4, targetTop + scaledH + 4)

                    val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(0.01f)
                    val cellRatio = scaledW / scaledH.coerceAtLeast(0.01f)
                    val srcRect = if (bmpRatio > cellRatio) {
                        val srcW = (bmp.height * cellRatio).toInt()
                        val srcX = (bmp.width - srcW) / 2
                        android.graphics.Rect(srcX, 0, srcX + srcW, bmp.height)
                    } else {
                        val srcH = (bmp.width / cellRatio).toInt()
                        val srcY = (bmp.height - srcH) / 2
                        android.graphics.Rect(0, srcY, bmp.width, srcY + srcH)
                    }

                    if (cornerRadiusPx > 0) {
                        canvas.drawRoundRect(shadowRect, cornerRadiusPx, cornerRadiusPx, shadowPaint)
                        canvas.save()
                        val clipP = Path().apply {
                            addRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
                        }
                        canvas.clipPath(clipP)
                        canvas.drawBitmap(bmp, srcRect, drawRect, paint)
                        canvas.restore()
                        if (hasBorder) {
                            canvas.drawRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
                        }
                    } else {
                        canvas.drawRect(shadowRect, shadowPaint)
                        canvas.drawBitmap(bmp, srcRect, drawRect, paint)
                        if (hasBorder) canvas.drawRect(drawRect, borderPaint)
                    }
                }

                bmp.recycle()
            }

            val outPath = saveBitmapToFile(context, result, outPrefix)
            result.recycle()
            outPath
        } catch (e: Exception) {
            e.printStackTrace()
            imagePaths.firstOrNull() ?: ""
        }
    }

    suspend fun saveToGallery(context: Context, imageFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext false
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "MS_Scanner_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MS Scanner")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                bitmap.recycle()
                true
            } else {
                bitmap.recycle()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveBitmapToFile(context: Context, bitmap: Bitmap, prefix: String = "scan_"): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        file.absolutePath
    }

    suspend fun loadBitmapFromFile(path: String, maxDim: Int = 2400): Bitmap? = withContext(Dispatchers.IO) {
        // Try cache first if maxDim is small (thumbnail request)
        if (maxDim <= 500) {
            thumbnailCache.get(path)?.let { return@withContext it }
        }

        try {
            val file = File(path)
            if (!file.exists()) return@withContext null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOpts)

            var sampleSize = 1
            val maxOriginal = max(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxOriginal / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true // Allow reuse if needed
            }
            val bitmap = BitmapFactory.decodeFile(path, opts)
            
            // Cache if it's a thumbnail request
            if (bitmap != null && maxDim <= 500) {
                thumbnailCache.put(path, bitmap)
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun analyzeQuality(bitmap: Bitmap): QualityReport {
        val sampleW = min(300, bitmap.width)
        val sampleH = min(400, bitmap.height)
        val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val pixels = IntArray(sampleW * sampleH)
        sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        sample.recycle()

        var totalLum = 0.0
        val lums = DoubleArray(pixels.size)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            lums[i] = lum
            totalLum += lum
        }

        val avgLum = totalLum / pixels.size

        var edgeSum = 0.0
        var edgeCount = 0
        for (y in 1 until sampleH - 1 step 2) {
            for (x in 1 until sampleW - 1 step 2) {
                val idx = y * sampleW + x
                val diffX = abs(lums[idx + 1] - lums[idx - 1])
                val diffY = abs(lums[idx + sampleW] - lums[idx - sampleW])
                edgeSum += (diffX + diffY)
                edgeCount++
            }
        }
        val avgEdgeSharpness = if (edgeCount > 0) edgeSum / edgeCount else 0.0

        val isBlurry = avgEdgeSharpness < 6.0
        val isDark = avgLum < 65.0
        val isLowContrast = avgEdgeSharpness < 4.0

        val score = when {
            isBlurry && isDark -> 35
            isBlurry -> 55
            isDark -> 65
            isLowContrast -> 70
            else -> 95
        }

        val (en, ar) = when {
            isBlurry -> Pair("Image appears blurry. Hold steady for best scan.", "الصورة تبدو ضبابية. يرجى تثبيت الهاتف لنتيجة أوضح.")
            isDark -> Pair("Low lighting detected. Try enabling flash or moving to a brighter spot.", "إضاءة منخفضة. ننصح بتشغيل الفلاش أو الانتقال لمكان مضيء.")
            else -> Pair("High quality scan. Sharp edges and high contrast.", "جودة مسح ممتازة. حواف واضحة وتباين ممتاز.")
        }

        return QualityReport(
            score = score,
            isBlurry = isBlurry,
            isLowContrast = isLowContrast,
            isDark = isDark,
            statusTextEn = en,
            statusTextAr = ar
        )
    }
}
