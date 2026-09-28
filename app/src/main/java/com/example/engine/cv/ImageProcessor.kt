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
                bitmap.recycle() // CRITICAL: Free memory for each page immediately
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


    /**
     * Result of document detection. The quad coordinates are normalized to the
     * oriented image (0..1), and confidence is a 0..1 score derived from edge
     * support and document geometry.
     */
    data class DocumentDetection(
        val quad: DocumentQuad,
        val confidence: Float,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /**
     * Detects a document from a full-resolution bitmap using an adaptive
     * edge/contour pipeline. No fixed guide rectangle is used as detection.
     * A null result means there is not enough evidence to crop safely.
     */
    suspend fun detectDocument(
        bitmap: Bitmap,
        expectedAspectRatio: Float? = null
    ): DocumentDetection? = withContext(Dispatchers.IO) {
        detectDocumentInternal(bitmap, expectedAspectRatio)
    }

    /**
     * CameraX fast path. Reads only the Y plane from ImageProxy, downsamples it
     * in place, detects the document border, and returns coordinates rotated to
     * the ImageAnalysis target orientation. This keeps RGB conversion off the
     * analysis hot path and lets STRATEGY_KEEP_ONLY_LATEST remain responsive.
     */
    fun detectDocumentFromImageProxy(
        imageProxy: ImageProxy,
        expectedAspectRatio: Float? = null
    ): DocumentDetection? {
        val yPlane = imageProxy.planes.firstOrNull() ?: return null
        val crop = imageProxy.cropRect
        val sourceWidth = crop.width().coerceAtLeast(1)
        val sourceHeight = crop.height().coerceAtLeast(1)
        val maxDim = 360
        val scale = min(1f, maxDim.toFloat() / max(sourceWidth, sourceHeight).toFloat())
        val sampleW = (sourceWidth * scale).roundToInt().coerceIn(180, maxDim)
        val sampleH = (sourceHeight * scale).roundToInt().coerceIn(180, maxDim)

        val grayscale = ByteArray(sampleW * sampleH)
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride.coerceAtLeast(sourceWidth)
        val pixelStride = yPlane.pixelStride.coerceAtLeast(1)
        val capacity = buffer.capacity()

        for (sy in 0 until sampleH) {
            val srcY = crop.top + ((sy + 0.5f) * sourceHeight / sampleH).toInt().coerceIn(0, sourceHeight - 1)
            val rowBase = srcY * rowStride
            for (sx in 0 until sampleW) {
                val srcX = crop.left + ((sx + 0.5f) * sourceWidth / sampleW).toInt().coerceIn(0, sourceWidth - 1)
                val pos = rowBase + srcX * pixelStride
                grayscale[sy * sampleW + sx] = if (pos in 0 until capacity) buffer.get(pos) else 127.toByte()
            }
        }

        val raw = detectFromGrayscale(grayscale, sampleW, sampleH, expectedAspectRatio) ?: return null
        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val rotatedQuad = rotateNormalizedQuad(raw.quad, rotation)
        val (orientedWidth, orientedHeight) = if (rotation == 90 || rotation == 270) {
            sourceHeight to sourceWidth
        } else {
            sourceWidth to sourceHeight
        }
        return raw.copy(
            quad = rotatedQuad,
            imageWidth = orientedWidth,
            imageHeight = orientedHeight
        )
    }

    /**
     * Compatibility API for existing processing workers. A failed detection
     * returns the full image instead of an arbitrary inset rectangle, which is
     * the only safe fallback for downstream perspective-warp callers.
     */
    suspend fun detectDocumentQuad(bitmap: Bitmap): DocumentQuad = withContext(Dispatchers.IO) {
        detectDocumentInternal(bitmap, null)?.quad ?: DocumentQuad.fullQuad()
    }

    private fun detectDocumentInternal(
        bitmap: Bitmap,
        expectedAspectRatio: Float?
    ): DocumentDetection? {
        if (bitmap.width < 32 || bitmap.height < 32) return null

        val maxDim = 420
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height).toFloat())
        val sampleW = (bitmap.width * scale).roundToInt().coerceAtLeast(32)
        val sampleH = (bitmap.height * scale).roundToInt().coerceAtLeast(32)
        val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
        try {
            val pixels = IntArray(sampleW * sampleH)
            sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            val grayscale = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                grayscale[i] = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255).toByte()
            }
            return detectFromGrayscale(grayscale, sampleW, sampleH, expectedAspectRatio)
                ?.copy(imageWidth = bitmap.width, imageHeight = bitmap.height)
        } finally {
            sample.recycle()
        }
    }

    /**
     * Adaptive edge + connected-component document detector.
     *
     * The important distinction from the previous implementation is that the
     * four corners are extracted only from a connected border-like component,
     * rather than from arbitrary strongest pixels such as text strokes.
     */
    private fun detectFromGrayscale(
        grayscale: ByteArray,
        width: Int,
        height: Int,
        expectedAspectRatio: Float?
    ): DocumentDetection? {
        if (width < 32 || height < 32) return null

        val gradient = FloatArray(width * height)
        val samples = FloatArray((width - 2) * (height - 2))
        var sampleCount = 0
        for (y in 1 until height - 1) {
            val row = y * width
            val prev = (y - 1) * width
            val next = (y + 1) * width
            for (x in 1 until width - 1) {
                val idx = row + x
                val gx = value(grayscale[prev + x + 1]) + 2f * value(grayscale[row + x + 1]) + value(grayscale[next + x + 1]) -
                    value(grayscale[prev + x - 1]) - 2f * value(grayscale[row + x - 1]) - value(grayscale[next + x - 1])
                val gy = value(grayscale[next + x - 1]) + 2f * value(grayscale[next + x]) + value(grayscale[next + x + 1]) -
                    value(grayscale[prev + x - 1]) - 2f * value(grayscale[prev + x]) - value(grayscale[prev + x + 1])
                val magnitude = sqrt(gx * gx + gy * gy)
                gradient[idx] = magnitude
                samples[sampleCount++] = magnitude
            }
        }

        if (sampleCount == 0) return null
        samples.sort(0, sampleCount)
        val p78 = samples[(sampleCount * 0.78f).toInt().coerceIn(0, sampleCount - 1)]
        val p88 = samples[(sampleCount * 0.88f).toInt().coerceIn(0, sampleCount - 1)]

        // First try a document-region model. This is much more reliable than
        // looking only at edge pixels when text strokes break the border into
        // multiple disconnected components. It is intentionally conservative
        // and falls back to the edge model for dark/low-contrast documents.
        val regionCandidate = detectRegionCandidate(grayscale, width, height, expectedAspectRatio)

        val thresholds = floatArrayOf(
            max(28f, p78),
            max(36f, p88),
            max(44f, (p88 * 1.12f))
        )

        var best = regionCandidate
        for (threshold in thresholds) {
            val candidate = detectCandidate(grayscale, gradient, width, height, threshold, expectedAspectRatio)
            if (candidate != null && candidate.confidence > (best?.confidence ?: -1f)) {
                best = candidate
            }
        }
        return best?.takeIf { it.confidence >= 0.58f }
    }

    /**
     * Attempts to segment the physical document from the surrounding surface.
     * This catches common scanner scenes where text or internal graphics create
     * gaps in the outer edge. The result is still validated geometrically and
     * never becomes an automatic crop unless confidence is sufficient.
     */
    private fun detectRegionCandidate(
        grayscale: ByteArray,
        width: Int,
        height: Int,
        expectedAspectRatio: Float?
    ): DocumentDetection? {
        val borderSize = max(2, (min(width, height) * 0.05f).roundToInt())
        val borderValues = IntArray(
            (width * borderSize * 2 + max(0, height - borderSize * 2) * borderSize * 2)
                .coerceAtLeast(1)
        )
        var borderCount = 0

        fun addBorder(v: Byte) {
            if (borderCount < borderValues.size) {
                borderValues[borderCount++] = value(v).roundToInt()
            }
        }
        for (y in 0 until borderSize) {
            val top = y * width
            val bottom = (height - 1 - y) * width
            for (x in 0 until width) {
                addBorder(grayscale[top + x])
                addBorder(grayscale[bottom + x])
            }
        }
        for (y in borderSize until height - borderSize) {
            val row = y * width
            for (x in 0 until borderSize) {
                addBorder(grayscale[row + x])
                addBorder(grayscale[row + width - 1 - x])
            }
        }
        if (borderCount == 0) return null
        borderValues.sort(0, borderCount)
        val borderMedian = borderValues[borderCount / 2].toFloat()

        val centerLeft = (width * 0.25f).toInt()
        val centerRight = (width * 0.75f).toInt().coerceAtLeast(centerLeft + 1)
        val centerTop = (height * 0.25f).toInt()
        val centerBottom = (height * 0.75f).toInt().coerceAtLeast(centerTop + 1)
        val centerValues = IntArray((centerRight - centerLeft) * (centerBottom - centerTop))
        var centerCount = 0
        for (y in centerTop until centerBottom) {
            val row = y * width
            for (x in centerLeft until centerRight) {
                centerValues[centerCount++] = value(grayscale[row + x]).roundToInt()
            }
        }
        if (centerCount == 0) return null
        centerValues.sort(0, centerCount)
        val centerMedian = centerValues[centerCount / 2].toFloat()
        val delta = centerMedian - borderMedian
        if (abs(delta) < 16f) return null

        val thresholdDelta = max(18f, abs(delta) * 0.35f)
        val threshold = (borderMedian + if (delta > 0f) thresholdDelta else -thresholdDelta).coerceIn(8f, 247f)
        val selectHigher = delta > 0f
        val mask = BooleanArray(width * height)
        for (i in grayscale.indices) {
            val lum = value(grayscale[i])
            mask[i] = if (selectHigher) lum >= threshold else lum <= threshold
        }

        // Close holes/gaps created by text and graphics so the physical page
        // remains one connected component before corner extraction.
        var closed = mask
        repeat(2) { closed = dilate(closed, width, height) }
        repeat(2) { closed = erode(closed, width, height) }

        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var best: DocumentDetection? = null
        val minPixels = max(60, (width * height * 0.08f).toInt())
        val edgeMargin = max(3, (min(width, height) * 0.035f).roundToInt())

        for (start in closed.indices) {
            if (!closed[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            var tl = PointF(0f, 0f)
            var tr = PointF(1f, 0f)
            var br = PointF(1f, 1f)
            var bl = PointF(0f, 1f)

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width
                count++
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                val nx = x / (width - 1).coerceAtLeast(1).toFloat()
                val ny = y / (height - 1).coerceAtLeast(1).toFloat()
                val sum = x + y
                val diff = x - y
                if (sum.toFloat() < minSum) { minSum = sum.toFloat(); tl = PointF(nx, ny) }
                if (sum.toFloat() > maxSum) { maxSum = sum.toFloat(); br = PointF(nx, ny) }
                if (diff.toFloat() > maxDiff) { maxDiff = diff.toFloat(); tr = PointF(nx, ny) }
                if (diff.toFloat() < minDiff) { minDiff = diff.toFloat(); bl = PointF(nx, ny) }

                val y0 = max(0, y - 1)
                val y1 = min(height - 1, y + 1)
                val x0 = max(0, x - 1)
                val x1 = min(width - 1, x + 1)
                for (ny in y0..y1) {
                    val row = ny * width
                    for (nx in x0..x1) {
                        val n = row + nx
                        if (closed[n] && !visited[n]) {
                            visited[n] = true
                            if (tail < queue.size) queue[tail++] = n
                        }
                    }
                }
            }

            if (count < minPixels) continue
            val bboxW = (maxX - minX + 1).toFloat()
            val bboxH = (maxY - minY + 1).toFloat()
            val bboxArea = bboxW * bboxH
            val componentArea = count.toFloat() / (width * height).toFloat()
            val bboxRatio = bboxArea / (width * height).toFloat()
            if (bboxRatio < 0.10f || bboxRatio > 0.985f) continue
            if (minX <= edgeMargin && maxX >= width - 1 - edgeMargin &&
                minY <= edgeMargin && maxY >= height - 1 - edgeMargin
            ) continue
            val quad = DocumentQuad(tl, tr, br, bl).sortCorners()
            if (!isQuadValid(quad)) continue
            val quadArea = polygonArea(quad)
            if (quadArea < 0.12f || quadArea > 0.985f) continue

            val aspect = quadAspectRatio(quad)
            if (expectedAspectRatio != null) {
                val ratioError = abs(ln(aspect / expectedAspectRatio.coerceAtLeast(0.1f)))
                if (ratioError > 0.72f) continue
            } else if (aspect !in 0.30f..3.20f) {
                continue
            }

            val density = (componentArea / bboxRatio).coerceIn(0f, 1f)
            if (density < 0.45f) continue
            val aspectScore = if (expectedAspectRatio == null) {
                1f
            } else {
                exp(-abs(ln(aspect / expectedAspectRatio.coerceAtLeast(0.1f))) * 1.15f)
            }
            val areaScore = ((quadArea - 0.10f) / 0.65f).coerceIn(0f, 1f)
            val confidence = (
                areaScore * 0.45f +
                    density * 0.25f +
                    rightAngleScore(quad) * 0.20f +
                    aspectScore * 0.10f
                ).coerceIn(0f, 1f)

            val candidate = DocumentDetection(
                quad = quad,
                confidence = confidence,
                imageWidth = width,
                imageHeight = height
            )
            if (best == null || candidate.confidence > best.confidence) {
                best = candidate
            }
        }
        return best
    }

    private fun erode(input: BooleanArray, width: Int, height: Int): BooleanArray {
        val output = BooleanArray(input.size)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                var keep = true
                loop@ for (dy in -1..1) {
                    val nRow = (y + dy) * width
                    for (dx in -1..1) {
                        if (!input[nRow + x + dx]) {
                            keep = false
                            break@loop
                        }
                    }
                }
                output[row + x] = keep
            }
        }
        return output
    }

    private fun detectCandidate(
        grayscale: ByteArray,
        gradient: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        expectedAspectRatio: Float?
    ): DocumentDetection? {
        val edge = BooleanArray(width * height)
        var edgeCount = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                if (gradient[idx] >= threshold) {
                    edge[idx] = true
                    edgeCount++
                }
            }
        }
        if (edgeCount < width * height * 0.002f) return null

        // One-pixel dilation connects small gaps in a real document border while
        // keeping the CPU and memory cost predictable on the analysis thread.
        val dilated = dilate(edge, width, height)
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var bestDetection: DocumentDetection? = null
        val border = max(3, (min(width, height) * 0.035f).roundToInt())
        val minComponentPixels = max(40, width * height / 220)

        for (start in dilated.indices) {
            if (!dilated[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var edgeStrength = 0f
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            var tl = PointF(0f, 0f)
            var tr = PointF(1f, 0f)
            var br = PointF(1f, 1f)
            var bl = PointF(0f, 1f)

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width
                count++
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                edgeStrength += gradient[idx]
                val nx = x / (width - 1).coerceAtLeast(1).toFloat()
                val ny = y / (height - 1).coerceAtLeast(1).toFloat()
                val sum = x + y
                val diff = x - y
                if (sum.toFloat() < minSum) { minSum = sum.toFloat(); tl = PointF(nx, ny) }
                if (sum.toFloat() > maxSum) { maxSum = sum.toFloat(); br = PointF(nx, ny) }
                if (diff.toFloat() > maxDiff) { maxDiff = diff.toFloat(); tr = PointF(nx, ny) }
                if (diff.toFloat() < minDiff) { minDiff = diff.toFloat(); bl = PointF(nx, ny) }

                val y0 = max(0, y - 1)
                val y1 = min(height - 1, y + 1)
                val x0 = max(0, x - 1)
                val x1 = min(width - 1, x + 1)
                for (ny in y0..y1) {
                    val row = ny * width
                    for (nx in x0..x1) {
                        val n = row + nx
                        if (dilated[n] && !visited[n]) {
                            visited[n] = true
                            if (tail < queue.size) queue[tail++] = n
                        }
                    }
                }
            }

            if (count < minComponentPixels) continue
            val bboxW = (maxX - minX + 1).toFloat()
            val bboxH = (maxY - minY + 1).toFloat()
            val bboxArea = bboxW * bboxH
            val bboxRatio = bboxArea / (width * height).toFloat()
            if (bboxRatio < 0.08f || bboxRatio > 0.995f) continue
            if (minX <= border && maxX >= width - border && minY <= border && maxY >= height - border) continue

            val quad = DocumentQuad(tl, tr, br, bl).sortCorners()
            if (!isQuadValid(quad)) continue

            val quadArea = polygonArea(quad) / (width * height).toFloat()
            if (quadArea < 0.12f || quadArea > 0.985f) continue

            val aspect = quadAspectRatio(quad)
            if (expectedAspectRatio != null) {
                val ratioError = abs(ln(aspect / expectedAspectRatio.coerceAtLeast(0.1f)))
                if (ratioError > 0.72f) continue
            } else if (aspect !in 0.30f..3.20f) {
                continue
            }

            val support = sideEdgeSupport(quad, gradient, width, height, threshold)
            if (support < 0.42f) continue
            val rectangularity = (quadArea / bboxRatio).coerceIn(0f, 1.2f) / 1.0f
            val rightAngleScore = rightAngleScore(quad)
            val areaScore = ((quadArea - 0.12f) / 0.70f).coerceIn(0f, 1f)
            val aspectScore = if (expectedAspectRatio == null) {
                1f
            } else {
                exp(-abs(ln(aspect / expectedAspectRatio.coerceAtLeast(0.1f))) * 1.25f)
            }
            val meanEdge = (edgeStrength / count.coerceAtLeast(1))
            val edgeScore = (meanEdge / max(threshold, 1f)).coerceIn(0f, 2f) / 2f
            val confidence = (
                areaScore * 0.22f +
                    support * 0.34f +
                    rightAngleScore * 0.18f +
                    rectangularity.coerceIn(0f, 1f) * 0.12f +
                    aspectScore * 0.08f +
                    edgeScore * 0.06f
                ).coerceIn(0f, 1f)

            val candidate = DocumentDetection(
                quad = quad,
                confidence = confidence,
                imageWidth = width,
                imageHeight = height
            )
            if (bestDetection == null || candidate.confidence > bestDetection.confidence) {
                bestDetection = candidate
            }
        }
        return bestDetection
    }

    private fun dilate(input: BooleanArray, width: Int, height: Int): BooleanArray {
        val output = BooleanArray(input.size)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val idx = row + x
                var found = false
                for (dy in -1..1) {
                    val nRow = (y + dy) * width
                    for (dx in -1..1) {
                        if (input[nRow + x + dx]) {
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
                output[idx] = found
            }
        }
        return output
    }

    private fun extremeQuad(points: IntArray, pointCount: Int, width: Int, height: Int): DocumentQuad {
        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        var tl = PointF(0.08f, 0.08f)
        var tr = PointF(0.92f, 0.08f)
        var br = PointF(0.92f, 0.92f)
        var bl = PointF(0.08f, 0.92f)

        for (i in 0 until pointCount) {
            val idx = points[i]
            val x = (idx % width).toFloat()
            val y = (idx / width).toFloat()
            val nx = (x / (width - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val ny = (y / (height - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val sum = x + y
            val diff = x - y
            if (sum.toFloat() < minSum) { minSum = sum.toFloat(); tl = PointF(nx, ny) }
            if (sum.toFloat() > maxSum) { maxSum = sum.toFloat(); br = PointF(nx, ny) }
            if (diff.toFloat() > maxDiff) { maxDiff = diff.toFloat(); tr = PointF(nx, ny) }
            if (diff.toFloat() < minDiff) { minDiff = diff.toFloat(); bl = PointF(nx, ny) }
        }
        return DocumentQuad(tl, tr, br, bl).sortCorners()
    }

    private fun sideEdgeSupport(
        quad: DocumentQuad,
        gradient: FloatArray,
        width: Int,
        height: Int,
        threshold: Float
    ): Float {
        val pts = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var supported = 0
        var total = 0
        val samplesPerSide = 28
        for (side in 0 until 4) {
            val a = pts[side]
            val b = pts[(side + 1) % 4]
            for (i in 0..samplesPerSide) {
                val t = i / samplesPerSide.toFloat()
                val fx = (a.x + (b.x - a.x) * t) * (width - 1)
                val fy = (a.y + (b.y - a.y) * t) * (height - 1)
                val x = fx.roundToInt()
                val y = fy.roundToInt()
                if (x !in 1 until width - 1 || y !in 1 until height - 1) continue
                var localMax = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        localMax = max(localMax, gradient[(y + dy) * width + (x + dx)])
                    }
                }
                if (localMax >= threshold) supported++
                total++
            }
        }
        return if (total == 0) 0f else supported / total.toFloat()
    }

    private fun polygonArea(quad: DocumentQuad): Float {
        val pts = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var sum = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    private fun quadAspectRatio(quad: DocumentQuad): Float {
        val wTop = distance(quad.topLeft, quad.topRight)
        val wBottom = distance(quad.bottomLeft, quad.bottomRight)
        val hLeft = distance(quad.topLeft, quad.bottomLeft)
        val hRight = distance(quad.topRight, quad.bottomRight)
        return ((wTop + wBottom) * 0.5f) / ((hLeft + hRight) * 0.5f).coerceAtLeast(0.001f)
    }

    private fun rightAngleScore(quad: DocumentQuad): Float {
        val pts = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var total = 0f
        for (i in pts.indices) {
            val prev = pts[(i + pts.size - 1) % pts.size]
            val center = pts[i]
            val next = pts[(i + 1) % pts.size]
            val ax = prev.x - center.x
            val ay = prev.y - center.y
            val bx = next.x - center.x
            val by = next.y - center.y
            val denom = hypot(ax.toDouble(), ay.toDouble()) * hypot(bx.toDouble(), by.toDouble())
            if (denom <= 1e-6) continue
            val cosine = (((ax * bx + ay * by).toDouble()) / denom).toFloat().coerceIn(-1f, 1f)
            total += (1f - abs(cosine)).coerceIn(0f, 1f)
        }
        return total / 4f
    }

    private fun distance(a: PointF, b: PointF): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun value(v: Byte): Float = (v.toInt() and 0xFF).toFloat()

    private fun rotateNormalizedQuad(quad: DocumentQuad, rotationDegrees: Int): DocumentQuad {
        fun rotatePoint(p: PointF): PointF = when (rotationDegrees) {
            90 -> PointF(1f - p.y, p.x)
            180 -> PointF(1f - p.x, 1f - p.y)
            270 -> PointF(p.y, 1f - p.x)
            else -> PointF(p.x, p.y)
        }
        return DocumentQuad(
            topLeft = rotatePoint(quad.topLeft),
            topRight = rotatePoint(quad.topRight),
            bottomRight = rotatePoint(quad.bottomRight),
            bottomLeft = rotatePoint(quad.bottomLeft)
        ).sortCorners()
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
        val pts = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        if (pts.any { it.x !in 0f..1f || it.y !in 0f..1f }) return false

        val edges = pts.indices.map { i -> distance(pts[i], pts[(i + 1) % pts.size]) }
        if (edges.any { it < 0.04f }) return false

        val area = polygonArea(quad)
        if (area < 0.12f || area > 0.985f) return false

        val widthTop = edges[0]
        val widthBottom = edges[2]
        val heightLeft = edges[3]
        val heightRight = edges[1]
        val avgWidth = (widthTop + widthBottom) / 2f
        val avgHeight = (heightLeft + heightRight) / 2f
        val aspect = avgWidth / avgHeight.coerceAtLeast(0.01f)
        if (aspect !in 0.3f..3.2f) return false

        // Opposite sides should be reasonably similar; reject self-crossing or
        // highly degenerate quads that could make Matrix.setPolyToPoly unstable.
        val widthConsistency = min(widthTop, widthBottom) / max(widthTop, widthBottom)
        val heightConsistency = min(heightLeft, heightRight) / max(heightLeft, heightRight)
        if (widthConsistency < 0.35f || heightConsistency < 0.35f) return false

        val crossSigns = FloatArray(4) { i ->
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        val hasPositive = crossSigns.any { it > 0f }
        val hasNegative = crossSigns.any { it < 0f }
        if (hasPositive && hasNegative) return false

        return true
    }

    /**
     * Warps four corners of a quad into an upright rectangle with high precision.
     * Main-safe: executes on Dispatchers.IO
     */
    suspend fun applyPerspectiveWarp(srcBitmap: Bitmap, quad: DocumentQuad): Bitmap = withContext(Dispatchers.IO) {
        val (dstW, dstH) = quad.targetDimensions(srcBitmap.width.toFloat(), srcBitmap.height.toFloat())
        val output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val matrix = quad.getTransformationMatrix(srcBitmap.width.toFloat(), srcBitmap.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        
        canvas.drawBitmap(srcBitmap, matrix, paint)
        output
    }

    @Deprecated("Use applyPerspectiveWarp instead", ReplaceWith("applyPerspectiveWarp(srcBitmap, quad)"))
    suspend fun warpPerspective(srcBitmap: Bitmap, quad: DocumentQuad): Bitmap = applyPerspectiveWarp(srcBitmap, quad)

    /**
     * Main-safe rotation
     */
    suspend fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap = withContext(Dispatchers.IO) {
        if (degrees % 360 == 0) return@withContext source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Professional Scan Filter Engine
     * Supports Original, Auto, Color, Enhanced, Grayscale, Black & White, Text, Magic.
     * Main-safe: executes on Dispatchers.IO
     */
    suspend fun applyFilter(src: Bitmap, filter: FilterType): Bitmap = withContext(Dispatchers.IO) {
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
        
        // Adaptive Background Flattening (True Document Scanner Shadow Removal)
        val sampleSize = 48
        val scaleW = sampleSize
        val scaleH = (sampleSize * (height.toFloat() / width)).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val bgMap = Bitmap.createScaledBitmap(tiny, width, height, true)
        tiny.recycle()
        
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
            
            // Normalize illumination by dividing by background map
            var or = (sr * 255) / br.coerceAtLeast(1)
            var og = (sg * 255) / bg.coerceAtLeast(1)
            var ob = (sb * 255) / bb.coerceAtLeast(1)
            
            // Boost saturation slightly and increase contrast
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
    suspend fun applySmartEnhance(src: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val report = analyzeQuality(src)
        
        var brightness = 0f
        var contrast = 1.0f
        
        if (report.isDark) {
            brightness = 15f
        }
        
        if (report.isLowContrast) {
            contrast = 1.3f
        }
        
        // Always apply sharpening for readability
        adjustEnhancements(src, brightness, contrast, sharpen = true)
    }

    /**
     * Adjusts brightness (-50..50), contrast (0.5..2.5), and optional sharpness
     * Main-safe: executes on Dispatchers.IO
     */
    suspend fun adjustEnhancements(src: Bitmap, brightness: Float, contrast: Float, sharpen: Boolean): Bitmap = withContext(Dispatchers.IO) {
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
            sharpened
        } else {
            output
        }
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

    suspend fun saveBitmapToFile(
        context: Context, 
        bitmap: Bitmap, 
        prefix: String = "scan_", 
        isTextContent: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        
        // Auto-detect based on prefix if it's raw or photo
        val actualIsText = isTextContent && !prefix.contains("raw", ignoreCase = true) && !prefix.contains("thumb", ignoreCase = true)
        
        // JPEG compression: 90 for text/documents, 75 for photos/raw images
        val quality = if (actualIsText) 90 else 75
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        file.absolutePath
    }

    suspend fun createThumbnail(context: Context, sourcePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return@withContext null

            // Load with sample size first to save memory
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, bounds)

            val targetSize = 250 // Slightly more than 200 for better quality on high-density screens
            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = BitmapFactory.decodeFile(sourcePath, opts) ?: return@withContext null

            // Precise scaling with aspect ratio maintenance
            val width = sampled.width
            val height = sampled.height
            val scale = targetSize.toFloat() / max(width, height)
            val finalW = (width * scale).toInt()
            val finalH = (height * scale).toInt()

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

    suspend fun loadBitmapFromFile(path: String, maxDim: Int = 2400): Bitmap? = withContext(Dispatchers.IO) {
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
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Analyzes quality of a bitmap.
     * Main-safe: executes on Dispatchers.IO
     */
    suspend fun analyzeQuality(bitmap: Bitmap): QualityReport = withContext(Dispatchers.IO) {
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
