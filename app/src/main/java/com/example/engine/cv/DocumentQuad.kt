package com.example.engine.cv

import android.graphics.Matrix
import android.graphics.PointF
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Four document corners, NORMALIZED (0..1) to the upright (EXIF-applied) RAW image.
 *
 * Coordinate contract (single source of truth for the whole app):
 *  - A quad stored with a page (cropQuadJson / sidecar) is always relative to the RAW upright image.
 *  - User rotation is applied AFTER the perspective warp (warp -> rotate -> filter).
 *  - Display code converts with [rotated] and never mutates the stored quad's reference frame.
 *
 * Instances are treated as immutable (PointF values are copied on every transform).
 */
data class DocumentQuad(
    val topLeft: PointF = PointF(0f, 0f),
    val topRight: PointF = PointF(1f, 0f),
    val bottomRight: PointF = PointF(1f, 1f),
    val bottomLeft: PointF = PointF(0f, 1f)
) {
    companion object {
        /**
         * Kept for source compatibility. Previously returned a 6% inset rectangle, which silently
         * cropped pages whenever detection failed or no quad was stored. It now returns the
         * full image so that a missing quad never destroys content.
         */
        fun defaultQuad(): DocumentQuad = fullQuad()

        fun fullQuad(): DocumentQuad = DocumentQuad(
            PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f)
        )

        fun fromPoints(points: List<PointF>): DocumentQuad {
            require(points.size == 4) { "A quad needs exactly 4 points" }
            return DocumentQuad(
                PointF(points[0].x, points[0].y), PointF(points[1].x, points[1].y),
                PointF(points[2].x, points[2].y), PointF(points[3].x, points[3].y)
            )
        }

        /** Returns the full image when the JSON is blank or malformed (never an arbitrary inset). */
        fun fromJson(jsonStr: String?): DocumentQuad = fromJsonOrNull(jsonStr) ?: fullQuad()

        fun fromJsonOrNull(jsonStr: String?): DocumentQuad? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(jsonStr)
                DocumentQuad(
                    PointF(obj.getDouble("tlX").toFloat(), obj.getDouble("tlY").toFloat()),
                    PointF(obj.getDouble("trX").toFloat(), obj.getDouble("trY").toFloat()),
                    PointF(obj.getDouble("brX").toFloat(), obj.getDouble("brY").toFloat()),
                    PointF(obj.getDouble("blX").toFloat(), obj.getDouble("blY").toFloat())
                ).takeIf { q -> q.points().all { it.x.isFinite() && it.y.isFinite() } }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun points(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun point(index: Int): PointF = when (index) {
        0 -> topLeft
        1 -> topRight
        2 -> bottomRight
        else -> bottomLeft
    }

    fun withPoint(index: Int, p: PointF): DocumentQuad {
        val c = PointF(p.x, p.y)
        return when (index) {
            0 -> copy(topLeft = c)
            1 -> copy(topRight = c)
            2 -> copy(bottomRight = c)
            else -> copy(bottomLeft = c)
        }
    }

    /** True when this quad covers (almost) the whole image, i.e. warping would be a no-op. */
    fun isFullImage(epsilon: Float = 0.004f): Boolean =
        abs(topLeft.x) < epsilon && abs(topLeft.y) < epsilon &&
            abs(topRight.x - 1f) < epsilon && abs(topRight.y) < epsilon &&
            abs(bottomRight.x - 1f) < epsilon && abs(bottomRight.y - 1f) < epsilon &&
            abs(bottomLeft.x) < epsilon && abs(bottomLeft.y - 1f) < epsilon

    fun clamped(): DocumentQuad = DocumentQuad(
        PointF(topLeft.x.coerceIn(0f, 1f), topLeft.y.coerceIn(0f, 1f)),
        PointF(topRight.x.coerceIn(0f, 1f), topRight.y.coerceIn(0f, 1f)),
        PointF(bottomRight.x.coerceIn(0f, 1f), bottomRight.y.coerceIn(0f, 1f)),
        PointF(bottomLeft.x.coerceIn(0f, 1f), bottomLeft.y.coerceIn(0f, 1f))
    )

    /**
     * Orders the corners TL, TR, BR, BL (clockwise on screen, y pointing down).
     * Angular sort around the centroid is robust for any in-plane rotation; the start corner is
     * the one with minimum x+y.
     */
    fun sortCorners(): DocumentQuad {
        val pts = points()
        val cx = pts.sumOf { it.x.toDouble() } / 4.0
        val cy = pts.sumOf { it.y.toDouble() } / 4.0
        val sorted = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        var tlIdx = 0
        var minSum = Float.MAX_VALUE
        for (i in sorted.indices) {
            val s = sorted[i].x + sorted[i].y
            if (s < minSum) {
                minSum = s
                tlIdx = i
            }
        }
        return fromPoints((0..3).map { sorted[(tlIdx + it) % 4] })
    }

    /** Signed-area based convexity test (strict: rejects degenerate/collinear corners). */
    fun isConvex(): Boolean {
        val pts = points()
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-7f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Area in normalized units (1.0 == whole image). */
    fun area(): Float {
        val pts = points()
        var sum = 0f
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /**
     * Largest corner displacement between two quads, in normalized units.
     * Both quads must use the same reference frame.
     */
    fun maxCornerDistance(other: DocumentQuad): Float {
        val a = points()
        val b = other.points()
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot(a[i].x - b[i].x, a[i].y - b[i].y))
        return m
    }

    /**
     * Maps the quad into the frame of the same image rotated clockwise by [degrees]
     * (multiples of 90). Corner identity (index) is preserved on purpose.
     */
    fun rotated(degrees: Int): DocumentQuad {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return this
        fun r(p: PointF): PointF = when (d) {
            90 -> PointF(1f - p.y, p.x)
            180 -> PointF(1f - p.x, 1f - p.y)
            270 -> PointF(p.y, 1f - p.x)
            else -> PointF(p.x, p.y)
        }
        return DocumentQuad(r(topLeft), r(topRight), r(bottomRight), r(bottomLeft))
    }

    fun mirroredHorizontally(): DocumentQuad = DocumentQuad(
        PointF(1f - topRight.x, topRight.y),
        PointF(1f - topLeft.x, topLeft.y),
        PointF(1f - bottomLeft.x, bottomLeft.y),
        PointF(1f - bottomRight.x, bottomRight.y)
    )

    /** Exponential smoothing toward [target] (alpha=1 -> target). */
    fun lerpTo(target: DocumentQuad, alpha: Float): DocumentQuad {
        val a = alpha.coerceIn(0f, 1f)
        fun l(p: PointF, q: PointF) = PointF(p.x + (q.x - p.x) * a, p.y + (q.y - p.y) * a)
        return DocumentQuad(
            l(topLeft, target.topLeft), l(topRight, target.topRight),
            l(bottomRight, target.bottomRight), l(bottomLeft, target.bottomLeft)
        )
    }

    fun toJson(): String = JSONObject().apply {
        put("tlX", topLeft.x.toDouble()); put("tlY", topLeft.y.toDouble())
        put("trX", topRight.x.toDouble()); put("trY", topRight.y.toDouble())
        put("brX", bottomRight.x.toDouble()); put("brY", bottomRight.y.toDouble())
        put("blX", bottomLeft.x.toDouble()); put("blY", bottomLeft.y.toDouble())
    }.toString()

    fun toAbsolutePoints(width: Float, height: Float): FloatArray = floatArrayOf(
        topLeft.x * width, topLeft.y * height,
        topRight.x * width, topRight.y * height,
        bottomRight.x * width, bottomRight.y * height,
        bottomLeft.x * width, bottomLeft.y * height
    )

    /**
     * Output size of the rectified document, in pixels, for a source of [width] x [height].
     * Uses the longer of each pair of opposite sides so that no resolution is lost, and caps the
     * result to [maxSide] to bound memory.
     */
    fun targetDimensions(width: Float, height: Float, maxSide: Int = 4096): Pair<Int, Int> {
        val q = sortCorners()
        val p = q.toAbsolutePoints(width, height)
        val top = hypot(p[2] - p[0], p[3] - p[1])
        val bottom = hypot(p[4] - p[6], p[5] - p[7])
        val left = hypot(p[6] - p[0], p[7] - p[1])
        val right = hypot(p[4] - p[2], p[5] - p[3])
        var w = max(top, bottom).coerceAtLeast(16f)
        var h = max(left, right).coerceAtLeast(16f)
        val longest = max(w, h)
        if (longest > maxSide) {
            val s = maxSide / longest
            w *= s
            h *= s
        }
        return Pair(w.roundToInt().coerceAtLeast(16), h.roundToInt().coerceAtLeast(16))
    }

    /** Homography mapping the (sorted) source quad onto an axis-aligned rectangle of [dstW] x [dstH]. */
    fun getTransformationMatrix(srcWidth: Float, srcHeight: Float): Matrix {
        val (dstW, dstH) = targetDimensions(srcWidth, srcHeight)
        return getTransformationMatrix(srcWidth, srcHeight, dstW, dstH)
    }

    fun getTransformationMatrix(srcWidth: Float, srcHeight: Float, dstW: Int, dstH: Int): Matrix {
        val src = sortCorners().toAbsolutePoints(srcWidth, srcHeight)
        val dst = floatArrayOf(0f, 0f, dstW.toFloat(), 0f, dstW.toFloat(), dstH.toFloat(), 0f, dstH.toFloat())
        return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
    }

    /** Minimum side length in normalized units (used by gesture validation). */
    fun minSide(): Float {
        val p = points()
        var m = Float.MAX_VALUE
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            m = min(m, hypot(a.x - b.x, a.y - b.y))
        }
        return m
    }
}
