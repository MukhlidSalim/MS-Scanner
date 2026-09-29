package com.example.engine.cv

import android.content.Context
import android.graphics.*
import androidx.camera.core.ImageProxy
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

    suspend fun mergeToPdf(context: Context, imagePaths: List<String>): File = withContext(Dispatchers.IO) {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val outputFile = File(context.cacheDir, "shared_document_${System.currentTimeMillis()}.pdf")

        try {
            for ((index, path) in imagePaths.withIndex()) {
                val bitmap = loadBitmapFromFile(path) ?: continue

                // A4 size in points (1/72 inch): 595 x 842
                val pageWidth = 595
                val pageHeight = 842

                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val canvas = page.canvas
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }

                val scale = minOf(
                    pageWidth.toFloat() / bitmap.width,
                    pageHeight.toFloat() / bitmap.height
                )

                val scaledWidth = bitmap.width * scale
                val scaledHeight = bitmap.height * scale

                val left = (pageWidth - scaledWidth) / 2f
                val top = (pageHeight - scaledHeight) / 2f

                val destRect = android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)

                pdfDocument.finishPage(page)
                bitmap.recycle() // Free memory for each page immediately
            }

            java.io.FileOutputStream(outputFile).use { out ->
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

    // =====================================================================================
    // DOCUMENT DETECTION
    //
    // ROOT CAUSE of "the camera does not recognise documents and keeps the whole photo":
    // the previous in-file detector computed the candidate area as
    //     polygonArea(normalizedQuad) / (width * height)
    // i.e. an already-normalized area (0..1) divided a second time by the pixel count. Every
    // edge-based candidate therefore had an area of ~0.000001 and was rejected (< 0.12), so only
    // the brightness-region fallback could ever succeed. Capture then fell back to the full frame.
    //
    // All detection is now delegated to DocumentDetector (the single authoritative detector used
    // by the live analyzer, DocumentPipeline, BatchProcessingQueue and the crop editor).
    // Public signatures are unchanged so every existing caller keeps compiling.
    // =====================================================================================

    /**
     * Result of document detection. The quad coordinates are normalized to the
     * oriented image (0..1), and confidence is a 0..1 score.
     * Kept for API compatibility with existing callers.
     */
    data class DocumentDetection(
        val quad: DocumentQuad,
        val confidence: Float,
        val imageWidth: Int,
        val imageHeight: Int
    )

    private fun com.example.engine.cv.DocumentDetection.toLegacy(): DocumentDetection =
        DocumentDetection(quad = quad, confidence = confidence, imageWidth = imageWidth, imageHeight = imageHeight)

    /**
     * Detects a document on an upright bitmap. Null when there is not enough evidence to crop safely.
     * CPU bound: runs on Dispatchers.Default.
     */
    suspend fun detectDocument(
        bitmap: Bitmap,
        expectedAspectRatio: Float? = null
    ): DocumentDetection? = withContext(Dispatchers.Default) {
        runCatching { DocumentDetector.detect(bitmap, expectedAspectRatio) }.getOrNull()?.toLegacy()
    }

    /** CameraX fast path (Y plane only). Must be called on the analyzer thread. */
    fun detectDocumentFromImageProxy(
        imageProxy: ImageProxy,
        expectedAspectRatio: Float? = null
    ): DocumentDetection? =
        runCatching { DocumentDetector.detectFromImageProxy(imageProxy, expectedAspectRatio) }.getOrNull()?.toLegacy()

    /**
     * Compatibility API. A failed detection returns the full image (never an arbitrary inset
     * rectangle), which is the only safe fallback for downstream perspective-warp callers.
     */
    suspend fun detectDocumentQuad(bitmap: Bitmap): DocumentQuad = withContext(Dispatchers.Default) {
        val detected = runCatching { DocumentDetector.detect(bitmap, null) }.getOrNull()?.quad
        if (detected != null && DocumentDetector.isPlausible(detected)) detected else DocumentQuad.fullQuad()
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
     * Validates whether a quad represents a plausible document.
     * Uses the same rules as the detector so every workflow agrees on what "valid" means.
     */
    fun isQuadValid(quad: DocumentQuad): Boolean = DocumentDetector.isPlausible(quad)

    /** Mathematical convexity check for 4 points */
    fun isConvex(quad: DocumentQuad): Boolean {
        val pts = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var lastSign = 0f
        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val p3 = pts[(i + 2) % 4]
            val cross = (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x)
            if (i == 0) {
                lastSign = cross
            } else if (cross * lastSign < 0) {
                return false
            }
        }
        return true
    }

    /**
     * Warps four corners of a quad into an upright rectangle (full homography).
     * Delegates to DocumentPipeline.warp so every workflow produces identical output.
     * Never returns the source instance: callers of this legacy API recycle inputs/outputs independently.
     */
    suspend fun applyPerspectiveWarp(srcBitmap: Bitmap, quad: DocumentQuad): Bitmap = withContext(Dispatchers.Default) {
        val out = DocumentPipeline.warp(srcBitmap, quad)
        if (out === srcBitmap) srcBitmap.copy(srcBitmap.config ?: Bitmap.Config.ARGB_8888, true) else out
    }

    @Deprecated("Use applyPerspectiveWarp instead", ReplaceWith("applyPerspectiveWarp(srcBitmap, quad)"))
    suspend fun warpPerspective(srcBitmap: Bitmap, quad: DocumentQuad): Bitmap = applyPerspectiveWarp(srcBitmap, quad)

    /**
     * Main-safe rotation
     */
    suspend fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap = withContext(Dispatchers.Default) {
        if (degrees % 360 == 0) return@withContext source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Professional Scan Filter Engine
     * Main-safe: executes on Dispatchers.Default
     */
    suspend fun applyFilter(src: Bitmap, filter: FilterType): Bitmap = withContext(Dispatchers.Default) {
        when (filter) {
            FilterType.ORIGINAL -> src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            FilterType.AUTO -> applyAutoScan(src)
            FilterType.MAGIC -> applyMagicColor(src)
            FilterType.COLOR -> applyColorEnhancement(src)
            FilterType.ENHANCED -> applyEnhanced(src)
            FilterType.GRAYSCALE -> applyGrayscale(src)
            FilterType.BLACK_WHITE -> applyHighContrastBW(src)
            FilterType.TEXT -> applyTextSharpening(src)
            FilterType.DOCUMENT -> applyCleanDocument(src)
            FilterType.HIGH_CONTRAST -> applyHighContrast(src)
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
     * Magic Color filter: adaptive background flattening (shadow removal) + contrast
     */
    private fun applyMagicColor(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        val sampleSize = 48
        val scaleW = sampleSize
        val scaleH = (sampleSize * (height.toFloat() / width)).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val bgMap = Bitmap.createScaledBitmap(tiny, width, height, true)
        if (tiny !== bgMap) tiny.recycle()

        val srcPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        bgMap.getPixels(bgPixels, 0, width, 0, 0, width, height)
        bgMap.recycle()

        val outPixels = IntArray(width * height)

        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val sr = (p shr 16) and 0xFF
            val sg = (p shr 8) and 0xFF
            val sb = p and 0xFF

            val bp = bgPixels[i]
            val br = (bp shr 16) and 0xFF
            val bg = (bp shr 8) and 0xFF
            val bb = bp and 0xFF

            var or = (sr * 255) / br.coerceAtLeast(1)
            var og = (sg * 255) / bg.coerceAtLeast(1)
            var ob = (sb * 255) / bb.coerceAtLeast(1)

            val lum = 0.299f * or + 0.587f * og + 0.114f * ob
            val sat = 1.25f
            or = (lum + (or - lum) * sat).toInt()
            og = (lum + (og - lum) * sat).toInt()
            ob = (lum + (ob - lum) * sat).toInt()

            val contrast = 1.35f
            or = (((or / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()
            og = (((og / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()
            ob = (((ob / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()

            outPixels[i] = (0xFF shl 24) or (or.coerceIn(0, 255) shl 16) or (og.coerceIn(0, 255) shl 8) or ob.coerceIn(0, 255)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
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
        cm.setSaturation(0.05f)

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

        val sharpened = applySharpenMask(output)
        if (sharpened !== output) output.recycle()
        return sharpened
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
     * High Contrast filter
     */
    private fun applyHighContrast(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 2.0f
        val brightness = -20f
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
     * Intelligent Smart Enhance: analyzes image quality and applies optimized
     * adjustments for brightness, contrast, and clarity.
     */
    suspend fun applySmartEnhance(src: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val report = analyzeQuality(src)

        var brightness = 0f
        var contrast = 1.0f

        if (report.isDark) {
            brightness = 15f
        }

        if (report.isLowContrast) {
            contrast = 1.3f
        }

        adjustEnhancements(src, brightness, contrast, sharpen = true)
    }

    /**
     * Adjusts brightness (-50..50), contrast (0.5..2.5), and optional sharpness
     * Main-safe: executes on Dispatchers.Default
     */
    suspend fun adjustEnhancements(src: Bitmap, brightness: Float, contrast: Float, sharpen: Boolean): Bitmap = withContext(Dispatchers.Default) {
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
            if (sharpened !== output) output.recycle()
            sharpened
        } else {
            output
        }
    }

    /**
     * Real 3x3 unsharp-mask convolution (the previous implementation only redrew the image
     * scaled by 0.2% at alpha 40, which blurred instead of sharpening).
     */
    private fun applySharpenMask(src: Bitmap): Bitmap {
        return try {
            val w = src.width
            val h = src.height
            if (w < 3 || h < 3) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            val inPx = IntArray(w * h)
            src.getPixels(inPx, 0, w, 0, 0, w, h)
            val outPx = inPx.copyOf()
            val amount = 0.6f
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val c = inPx[i]
                    var sr = 0; var sg = 0; var sb = 0
                    for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                        val p = inPx[n]
                        sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                    }
                    val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                    val r = (cr + amount * (cr - sr / 4f)).roundToInt().coerceIn(0, 255)
                    val g = (cg + amount * (cg - sg / 4f)).roundToInt().coerceIn(0, 255)
                    val b = (cb + amount * (cb - sb / 4f)).roundToInt().coerceIn(0, 255)
                    outPx[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            output.setPixels(outPx, 0, w, 0, 0, w, h)
            output
        } catch (e: Throwable) {
            src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
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
    ): String = withContext(Dispatchers.Default) {
        try {
            val rawFrontBmp = loadBitmapFromFile(frontPath, 1800)
            val rawBackBmp = if (backPath.isNotBlank() && backPath != frontPath) {
                loadBitmapFromFile(backPath, 1800)
            } else null

            if (rawFrontBmp == null) return@withContext frontPath

            val frontBmp = if (swapOrder && rawBackBmp != null) rawBackBmp else rawFrontBmp
            val backBmp = if (swapOrder && rawBackBmp != null) rawFrontBmp else rawBackBmp

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
                    val clampedScale = scale.coerceIn(0.40f, 1.10f)
                    val targetW = canvasW * 0.44f * clampedScale
                    val frontRatio = frontBmp.width.toFloat() / frontBmp.height.toFloat().coerceAtLeast(0.1f)
                    val backRatio = backBmp.width.toFloat() / backBmp.height.toFloat().coerceAtLeast(0.1f)
                    val targetHFront = targetW / frontRatio
                    val targetHBack = targetW / backRatio

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

                    val frontLeft = (canvasW - targetW) / 2f
                    val frontTop = startY
                    val frontRect = RectF(frontLeft, frontTop, frontLeft + targetW, frontTop + targetHFront)
                    val frontShadow = RectF(frontLeft + 5, frontTop + 5, frontLeft + targetW + 5, frontTop + targetHFront + 5)
                    canvas.drawRoundRect(frontShadow, cornerRadius, cornerRadius, shadowPaint)
                    canvas.drawBitmap(frontBmp, null, frontRect, paint)
                    if (hasBorder) canvas.drawRoundRect(frontRect, cornerRadius, cornerRadius, borderPaint)

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
    ): String = withContext(Dispatchers.Default) {
        if (imagePaths.isEmpty()) return@withContext ""
        if (imagePaths.size == 1) return@withContext imagePaths[0]

        try {
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

    suspend fun saveBitmapToFile(
        context: Context,
        bitmap: Bitmap,
        prefix: String = "scan_",
        isTextContent: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")

        // Raw sources keep higher quality: every re-crop / filter re-renders from them.
        val isRaw = prefix.contains("raw", ignoreCase = true)
        val isThumb = prefix.contains("thumb", ignoreCase = true)
        val quality = when {
            isThumb -> 75
            isRaw -> 92
            isTextContent -> 90
            else -> 80
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        file.absolutePath
    }

    suspend fun createThumbnail(context: Context, sourcePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val targetSize = 250
            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = BitmapFactory.decodeFile(sourcePath, opts) ?: return@withContext null

            val width = sampled.width
            val height = sampled.height
            val scale = targetSize.toFloat() / max(width, height)
            val finalW = (width * scale).toInt().coerceAtLeast(1)
            val finalH = (height * scale).toInt().coerceAtLeast(1)

            val thumb = Bitmap.createScaledBitmap(sampled, finalW, finalH, true)
            if (thumb != sampled) sampled.recycle()

            val thumbPath = saveBitmapToFile(context, thumb, "thumb_")
            thumb.recycle()
            thumbPath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun mergeBitmapsVertical(bitmaps: List<Bitmap>): Bitmap = withContext(Dispatchers.Default) {
        val totalWidth = bitmaps.maxOf { it.width }
        val totalHeight = bitmaps.sumOf { it.height }
        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        var currentY = 0f
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, 0f, currentY, null)
            currentY += bitmap.height
        }
        result
    }

    suspend fun loadBitmapFromFile(path: String, maxDim: Int = 2400): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxOriginal = max(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxOriginal / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Throwable) {
            // OutOfMemoryError included: a single oversized page must not crash the app.
            null
        }
    }

    /**
     * Analyzes quality of a bitmap.
     * Main-safe: executes on Dispatchers.Default
     */
    suspend fun analyzeQuality(bitmap: Bitmap): QualityReport = withContext(Dispatchers.Default) {
        val sampleW = min(300, bitmap.width).coerceAtLeast(3)
        val sampleH = min(400, bitmap.height).coerceAtLeast(3)
        val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val pixels = IntArray(sampleW * sampleH)
        sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (sample !== bitmap) sample.recycle()

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

        QualityReport(
            score = score,
            isBlurry = isBlurry,
            isLowContrast = isLowContrast,
            isDark = isDark,
            statusTextEn = en,
            statusTextAr = ar
        )
    }
}
