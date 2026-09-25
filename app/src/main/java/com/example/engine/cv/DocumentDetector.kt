package com.example.engine.cv

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val quad: DocumentQuad,
    val isDetected: Boolean,
    val isStable: Boolean,
    val isCentered: Boolean,
    val stabilityProgress: Float, // 0f..1f for auto-capture countdown
    val guidanceEn: String,
    val guidanceAr: String
)

class DocumentDetector {

    private var lastQuad: DocumentQuad? = null
    private var stableFrameCount = 0
    private val REQUIRED_STABLE_FRAMES = 8 // ~0.8s at 10-15 analysis fps

    fun reset() {
        lastQuad = null
        stableFrameCount = 0
    }

    /**
     * Fast boundary detection on low-res preview frame
     */
    fun processFrame(bitmap: Bitmap): DetectionResult {
        val w = bitmap.width
        val h = bitmap.height

        // Downsample for micro-latency frame analysis
        val scale = 240f / max(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(32)
        val sh = (h * scale).toInt().coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, false)

        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        // Find edge boundaries using luminance thresholding
        var minX = sw
        var maxX = 0
        var minY = sh
        var maxY = 0

        // Find background luminance (average of outer border)
        var borderLum = 0.0
        var borderCount = 0
        for (x in 0 until sw) {
            borderLum += getLum(pixels[x])
            borderLum += getLum(pixels[(sh - 1) * sw + x])
            borderCount += 2
        }
        for (y in 0 until sh) {
            borderLum += getLum(pixels[y * sw])
            borderLum += getLum(pixels[y * sw + sw - 1])
            borderCount += 2
        }
        val bgLum = borderLum / borderCount

        // Scan for paper boundaries that contrast against background
        val contrastDelta = 22.0
        for (y in (sh * 0.05).toInt() until (sh * 0.95).toInt() step 2) {
            for (x in (sw * 0.05).toInt() until (sw * 0.95).toInt() step 2) {
                val lum = getLum(pixels[y * sw + x])
                if (abs(lum - bgLum) > contrastDelta) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val hasValidBox = (maxX - minX) > (sw * 0.35) && (maxY - minY) > (sh * 0.35)

        val candidateQuad = if (hasValidBox) {
            // Add subtle inset and convert to normalized points
            val nMinX = (minX.toFloat() / sw).coerceIn(0.04f, 0.35f)
            val nMaxX = (maxX.toFloat() / sw).coerceIn(0.65f, 0.96f)
            val nMinY = (minY.toFloat() / sh).coerceIn(0.04f, 0.35f)
            val nMaxY = (maxY.toFloat() / sh).coerceIn(0.65f, 0.96f)

            DocumentQuad(
                topLeft = PointF(nMinX, nMinY),
                topRight = PointF(nMaxX, nMinY),
                bottomRight = PointF(nMaxX, nMaxY),
                bottomLeft = PointF(nMinX, nMaxY)
            )
        } else {
            DocumentQuad.defaultQuad()
        }

        // Check stability against last quad
        val prev = lastQuad
        var isStable = false
        if (prev != null) {
            val drift = calculateDrift(prev, candidateQuad)
            if (drift < 0.035f) { // Less than 3.5% drift between frames
                stableFrameCount++
                if (stableFrameCount >= REQUIRED_STABLE_FRAMES) {
                    isStable = true
                }
            } else {
                stableFrameCount = max(0, stableFrameCount - 2)
            }
        } else {
            stableFrameCount = 0
        }
        lastQuad = candidateQuad

        val stabilityProgress = (stableFrameCount.toFloat() / REQUIRED_STABLE_FRAMES).coerceIn(0f, 1f)
        val isCentered = hasValidBox

        val (en, ar) = when {
            isStable && hasValidBox -> Pair("Capturing document…", "جاري التقاط المستند…")
            hasValidBox && stableFrameCount > 3 -> Pair("Hold steady…", "ثبّت الهاتف…")
            hasValidBox -> Pair("Document detected", "تم اكتشاف المستند")
            else -> Pair("Align document inside frame", "وجّه الكاميرا نحو حدود المستند")
        }

        return DetectionResult(
            quad = candidateQuad,
            isDetected = hasValidBox,
            isStable = isStable,
            isCentered = isCentered,
            stabilityProgress = stabilityProgress,
            guidanceEn = en,
            guidanceAr = ar
        )
    }

    private fun getLum(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun calculateDrift(q1: DocumentQuad, q2: DocumentQuad): Float {
        val d1 = hypot(q1.topLeft.x - q2.topLeft.x, q1.topLeft.y - q2.topLeft.y)
        val d2 = hypot(q1.topRight.x - q2.topRight.x, q1.topRight.y - q2.topRight.y)
        val d3 = hypot(q1.bottomRight.x - q2.bottomRight.x, q1.bottomRight.y - q2.bottomRight.y)
        val d4 = hypot(q1.bottomLeft.x - q2.bottomLeft.x, q1.bottomLeft.y - q2.bottomLeft.y)
        return (d1 + d2 + d3 + d4) / 4f
    }
}
