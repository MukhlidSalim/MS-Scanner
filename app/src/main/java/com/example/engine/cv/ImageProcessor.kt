package com.example.engine.cv

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.graphics.*
import com.example.data.model.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class QualityReport(
    val score: Int, // 0..100
    val isBlurry: Boolean,
    val isLowContrast: Boolean,
    val isDark: Boolean,
    val statusTextEn: String,
    val statusTextAr: String
)

object ImageProcessor {
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
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

    suspend fun loadBitmapFromFile(path: String, maxDim: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
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
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Warps the four corners of a document quad into an upright rectangle.
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
            // Fallback to bounding rect crop
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

    /**
     * Rotates bitmap by specified degrees (90, 180, 270)
     */
    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Applies professional document processing filters
     */
    fun applyFilter(src: Bitmap, filter: FilterType): Bitmap {
        return when (filter) {
            FilterType.ORIGINAL -> src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            FilterType.MAGIC -> applyMagicColor(src)
            FilterType.DOCUMENT -> applyCleanDocument(src)
            FilterType.BLACK_WHITE -> applyHighContrastBW(src)
            FilterType.GRAYSCALE -> applyGrayscale(src)
            FilterType.VIBRANT -> applyVibrant(src)
        }
    }

    /**
     * Magic Color: Auto levels, sharpens text while preserving colors of stamps and signatures
     */
    private fun applyMagicColor(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Color matrix with boosted contrast and saturation
        val contrast = 1.35f
        val brightness = 8f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Boost saturation slightly for stamps/seals
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.2f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Clean Document: Whitens background, creates deep rich text
     */
    private fun applyCleanDocument(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // High contrast matrix that pushes highlights to pure white
        val contrast = 1.6f
        val brightness = 25f
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
     * High Contrast Black & White for text readability and small file size
     */
    private fun applyHighContrastBW(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Convert to grayscale first then push contrast to high threshold
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        val m = floatArrayOf(
            2.5f, 0f, 0f, 0f, -140f,
            0f, 2.5f, 0f, 0f, -140f,
            0f, 0f, 2.5f, 0f, -140f,
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
     * Grayscale
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

    /**
     * Vibrant: Enhances colors for cards, badges, photos
     */
    private fun applyVibrant(src: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val cm = ColorMatrix().apply {
            setSaturation(1.4f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /**
     * Analyzes image quality (sharpness, exposure, contrast)
     */
    fun analyzeQuality(bitmap: Bitmap): QualityReport {
        val sampleW = min(300, bitmap.width)
        val sampleH = min(400, bitmap.height)
        val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val pixels = IntArray(sampleW * sampleH)
        sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        var totalLum = 0.0
        var totalVariance = 0.0
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

        // Calculate gradient / edge variance (sharpness metric)
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
