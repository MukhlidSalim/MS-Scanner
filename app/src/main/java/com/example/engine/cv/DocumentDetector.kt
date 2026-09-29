package com.example.engine.cv

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DocumentDetection(
    val quad: DocumentQuad,
    val confidence: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * Document border detector — OpenCV-backed (native C++ Canny/contours via JNI), replacing the
 * previous pure-Kotlin Hough-transform implementation.
 *
 * Why this replaces the manual engine:
 *  - Contour extraction (Imgproc.findContours) directly returns closed polygon candidates from the
 *    binary edge map; no need to reconstruct quads from line intersections (the old houghQuads()).
 *  - Canny + morphological close in native code is 10-40x faster than the per-pixel Kotlin loops
 *    that were doing convolution and voting by hand.
 *  - cornerSubPix gives true sub-pixel corner refinement (fraction-of-a-pixel accuracy) instead of
 *    the manual least-squares line refit.
 *
 * Public API is unchanged: DocumentPipeline, ImageProcessor, LiveDocumentAnalyzer and
 * CameraScanScreen call the same four functions with the same signatures as before.
 */
object DocumentDetector {
    const val MIN_CONFIDENCE = 0.60f
    const val AUTO_CAPTURE_CONFIDENCE = 0.70f

    private const val LIVE_MAX_SIDE = 500     // live preview: OpenCV is fast enough to afford more detail than the old 400px
    private const val STILL_MAX_SIDE = 900    // still capture / gallery import
    private const val MIN_AREA_RATIO = 0.12f
    private const val MAX_AREA_RATIO = 0.98f

    // ------------------------------------------------------------------ public entry points

    fun detect(bitmap: Bitmap, expectedAspectRatio: Float? = null): DocumentDetection? {
        if (!OpenCvBootstrap.ensureInitialized()) return null
        val (mat, scale) = toGray(bitmap, STILL_MAX_SIDE) ?: return null
        return try {
            val best = findBestQuad(mat, expectedAspectRatio, priorPx = null) ?: return null
            toDetection(best, scale, bitmap.width, bitmap.height)
        } finally {
            mat.release()
        }
    }

    fun detectWithPrior(bitmap: Bitmap, prior: DocumentQuad?, expectedAspectRatio: Float? = null): DocumentDetection? {
        if (prior == null) return detect(bitmap, expectedAspectRatio)
        if (!OpenCvBootstrap.ensureInitialized()) return null
        val (mat, scale) = toGray(bitmap, STILL_MAX_SIDE) ?: return null
        return try {
            val priorPx = toPixelQuad(prior, mat.cols(), mat.rows())
            val best = findBestQuad(mat, expectedAspectRatio, priorPx)
            if (best != null) toDetection(best, scale, bitmap.width, bitmap.height)
            else DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height) // held steady in preview: trust it
        } finally {
            mat.release()
        }
    }

    /** CameraX fast path: reads the Y plane directly (grayscale already), no Bitmap allocation. */
    fun detectFromImageProxy(
        imageProxy: ImageProxy,
        expectedAspectRatio: Float? = null,
        prior: DocumentQuad? = null
    ): DocumentDetection? {
        if (!OpenCvBootstrap.ensureInitialized()) return null
        val crop = imageProxy.cropRect
        val plane = imageProxy.planes.getOrNull(0) ?: return null
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Y-plane as a single-channel Mat; row width = rowStride (may include padding beyond cropRect).
        val full = Mat(imageProxy.height, rowStride, CvType.CV_8UC1)
        full.put(0, 0, bytes)
        val cropped = try {
            Mat(full, org.opencv.core.Rect(crop.left, crop.top, crop.width(), crop.height())).clone()
        } finally {
            full.release()
        }

        val scale = min(1.0, LIVE_MAX_SIDE.toDouble() / max(cropped.cols(), cropped.rows()))
        val small = if (scale < 1.0) {
            Mat().also { Imgproc.resize(cropped, it, Size(cropped.cols() * scale, cropped.rows() * scale)) }
        } else cropped
        if (small !== cropped) cropped.release()

        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        // Prior is in the ORIENTED frame; rotate it back to sensor space to match `small`.
        val sensorPrior = prior?.rotated((360 - rotation) % 360)?.sortCorners()
        val priorPx = sensorPrior?.let { toPixelQuad(it, small.cols(), small.rows()) }

        return try {
            val best = findBestQuad(small, expectedAspectRatio, priorPx) ?: return null
            val (ow, oh) = if (rotation == 90 || rotation == 270) crop.height() to crop.width() else crop.width() to crop.height()
            val normalized = toNormalizedQuad(best, small.cols(), small.rows())
            DocumentDetection(normalized.rotated(rotation).sortCorners(), best.confidence, ow, oh)
        } finally {
            small.release()
        }
    }

    fun isPlausible(quad: DocumentQuad): Boolean {
        if (quad.points().any { !it.x.isFinite() || !it.y.isFinite() || it.x !in -0.02f..1.02f || it.y !in -0.02f..1.02f }) return false
        if (!quad.isConvex()) return false
        val area = quad.area()
        if (area < 0.02f || area > 1.0001f) return false
        return quad.minSide() >= 0.03f
    }

    // ------------------------------------------------------------------ OpenCV core

    private class Candidate(val pointsPx: Array<PointF>, val confidence: Float)

    private fun toGray(bitmap: Bitmap, maxSide: Int): Pair<Mat, Double>? {
        if (bitmap.width < 32 || bitmap.height < 32) return null
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val scale = min(1.0, maxSide.toDouble() / max(gray.cols(), gray.rows()))
        if (scale >= 1.0) return gray to 1.0
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(gray.cols() * scale, gray.rows() * scale))
        gray.release()
        return resized to scale
    }

    /**
     * Edge -> close gaps -> contours -> polygon approximation -> pick best 4-point convex candidate.
     * [priorPx] (previous tracked quad, same pixel space as [gray]) breaks ties toward temporal
     * continuity so the overlay does not jump between the sheet and a printed frame inside it.
     */
    private fun findBestQuad(gray: Mat, expectedAspectRatio: Float?, priorPx: Array<PointF>?): Candidate? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        // Auto Canny thresholds from the median intensity (robust across lighting conditions;
        // a fixed threshold is what made the old detector miss white-on-white pages).
        val median = medianOf(blurred)
        val lower = max(0.0, 0.66 * median)
        val upper = min(255.0, 1.33 * median)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, lower, upper)
        blurred.release()

        // Bridges small gaps in a weak/broken border before contour extraction.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        edges.release(); hierarchy.release()

        val frameArea = gray.cols().toDouble() * gray.rows().toDouble()
        var best: Candidate? = null
        var bestScore = -1.0

        for (c in contours) {
            val area = Imgproc.contourArea(c)
            val ratio = area / frameArea
            if (ratio < MIN_AREA_RATIO || ratio > MAX_AREA_RATIO) { c.release(); continue }

            val c2f = MatOfPoint2f(*c.toArray())
            val perimeter = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * perimeter, true)
            c2f.release(); c.release()

            val pts = approx.toArray()
            approx.release()
            if (pts.size != 4) continue
            val mop = MatOfPoint(*pts)
            val convex = Imgproc.isContourConvex(mop)
            mop.release()
            if (!convex) continue

            val ordered = orderCorners(pts)
            val angleScore = rightAngleScore(ordered)
            if (angleScore < 0.60) continue

            var score = 0.55 * min(1.0, ratio / 0.5) + 0.45 * angleScore
            if (expectedAspectRatio != null && expectedAspectRatio > 0f) {
                val ar = quadAspectRatio(ordered)
                val dev = abs(ar - expectedAspectRatio) / expectedAspectRatio
                score *= max(0.5, 1.0 - dev * 0.7)
            }
            if (priorPx != null) {
                val dist = maxCornerDistance(ordered, priorPx) / max(gray.cols(), gray.rows())
                if (dist < 0.10) score += 0.15 * (1.0 - dist / 0.10) // continuity bonus, capped
            }
            if (score > bestScore) {
                bestScore = score
                best = Candidate(refineSubPixel(gray, ordered), score.toFloat().coerceIn(0f, 1f))
            }
        }
        return best
    }

    /** True sub-pixel corner refinement (fractional-pixel accuracy) around each approximate corner. */
    private fun refineSubPixel(gray: Mat, corners: Array<PointF>): Array<PointF> {
        val mop = MatOfPoint2f(*corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        return try {
            Imgproc.cornerSubPix(
                gray, mop, Size(5.0, 5.0), Size(-1.0, -1.0),
                TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.01)
            )
            mop.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
        } catch (_: Exception) {
            corners // refinement is best-effort; the approxPolyDP corners are still valid
        } finally {
            mop.release()
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun orderCorners(pts: Array<Point>): Array<PointF> {
        val p = pts.map { PointF(it.x.toFloat(), it.y.toFloat()) }
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun rightAngleScore(q: Array<PointF>): Double {
        var total = 0.0
        for (i in 0 until 4) {
            val prev = q[(i + 3) % 4]; val cur = q[i]; val next = q[(i + 1) % 4]
            val ax = prev.x - cur.x; val ay = prev.y - cur.y
            val bx = next.x - cur.x; val by = next.y - cur.y
            val den = hypot(ax.toDouble(), ay.toDouble()) * hypot(bx.toDouble(), by.toDouble())
            if (den < 1e-6) return 0.0
            total += 1.0 - abs((ax * bx + ay * by) / den).coerceIn(0.0, 1.0)
        }
        return total / 4.0
    }

    private fun quadAspectRatio(q: Array<PointF>): Float {
        fun d(a: PointF, b: PointF) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        val w = (d(q[0], q[1]) + d(q[3], q[2])) / 2.0
        val h = (d(q[0], q[3]) + d(q[1], q[2])) / 2.0
        val long = max(w, h); val short = max(1e-3, min(w, h))
        return (long / short).toFloat()
    }

    private fun maxCornerDistance(a: Array<PointF>, b: Array<PointF>): Float {
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot((a[i].x - b[i].x).toDouble(), (a[i].y - b[i].y).toDouble()).toFloat())
        return m
    }

    private fun toPixelQuad(quad: DocumentQuad, w: Int, h: Int): Array<PointF> {
        val s = quad.sortCorners().points()
        return arrayOf(
            PointF(s[0].x * w, s[0].y * h), PointF(s[1].x * w, s[1].y * h),
            PointF(s[2].x * w, s[2].y * h), PointF(s[3].x * w, s[3].y * h)
        )
    }

    private fun toNormalizedQuad(c: Candidate, w: Int, h: Int): DocumentQuad {
        val p = c.pointsPx
        fun n(pt: PointF) = PointF((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f))
        return DocumentQuad(n(p[0]), n(p[1]), n(p[2]), n(p[3]))
    }

    private fun toDetection(c: Candidate, scale: Double, origW: Int, origH: Int): DocumentDetection {
        // pointsPx are in the (possibly downscaled) working Mat's pixel space; divide by `scale`
        // to map back to the original bitmap, then normalize.
        val p = c.pointsPx.map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) }
        fun n(pt: PointF) = PointF((pt.x / origW).coerceIn(0f, 1f), (pt.y / origH).coerceIn(0f, 1f))
        val quad = DocumentQuad(n(p[0]), n(p[1]), n(p[2]), n(p[3]))
        return DocumentDetection(quad, c.confidence, origW, origH)
    }

    /** Median pixel intensity via a 256-bin histogram (used for auto Canny thresholds). */
    private fun medianOf(mat: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(mat), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = mat.rows() * mat.cols()
        var acc = 0.0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= total / 2.0) { hist.release(); return i.toDouble() }
        }
        hist.release()
        return 128.0
    }
}
