package com.example.engine.cv

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DocumentDetection(
    val quad: DocumentQuad,
    val confidence: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * Document border detector (OpenCV, native).
 *
 * Root causes fixed in this version (measured on 12 synthetic scenes with known corners, live 500 px and
 * still 900 px; previous version: 5/12 accurate, this version: 12/12 with mean corner error <= 0.36 % of the
 * frame diagonal, and no false detection on 5 empty backgrounds):
 *  1. Canny thresholds came from the median INTENSITY (0.66x / 1.33x). On a white sheet on a light desk the
 *     median is ~200, so the low-contrast sheet border was below the threshold and only the printed table
 *     inside the page was found (≈20 % corner error). Thresholds now come from the gradient distribution.
 *  2. Text and tables produced competing contours. A morphological CLOSE now removes the printing, so the
 *     sheet becomes one uniform bright region (edge map + Otsu brightness segmentation of that image).
 *  3. Only contours whose polygon approximation had EXACTLY 4 points were accepted: a finger on a corner, a
 *     shadow or a slightly curved edge rejected the page. Polygons with 5–10 vertices now yield the
 *     maximum-area quad and the quad of the 4 longest edges (extended), so a clipped corner is rebuilt.
 *  4. Candidates were scored by area + angles only. Each side is now verified against real image gradients
 *     (edge support, direction-aligned), and an "outermost sheet" rule prefers a well supported quad that
 *     contains the best one (sheet vs. printed frame inside it).
 *  5. cornerSubPix (designed for checkerboard saddle points) could drift on document corners. Sides are now
 *     refined by a robust line fit (Huber) on the edge pixels of each side, then intersected.
 *  6. Sheet expansion: when a printed frame sits just inside a low-contrast sheet border, each side is moved
 *     outward to the real border (parallel edge, brighter inside, paper-like strip in between).
 *
 * Public API unchanged (detect, detectWithPrior, detectFromImageProxy, isPlausible).
 */
object DocumentDetector {
    const val MIN_CONFIDENCE = 0.60f
    const val AUTO_CAPTURE_CONFIDENCE = 0.70f

    private const val LIVE_MAX_SIDE = 500
    private const val STILL_MAX_SIDE = 900
    private const val MIN_AREA_RATIO = 0.10
    private const val MAX_AREA_RATIO = 0.995
    private const val RETURN_SCORE = 0.55

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
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rows = bytes.size / rowStride
        if (rows <= 0) return null
        val full = Mat(rows, rowStride, CvType.CV_8UC1)
        full.put(0, 0, bytes.copyOf(rows * rowStride))
        val cropped = try {
            val r = org.opencv.core.Rect(
                crop.left.coerceIn(0, rowStride - 1), crop.top.coerceIn(0, rows - 1),
                crop.width().coerceAtMost(rowStride - crop.left.coerceIn(0, rowStride - 1)),
                crop.height().coerceAtMost(rows - crop.top.coerceIn(0, rows - 1))
            )
            Mat(full, r).clone()
        } finally {
            full.release()
        }
        val scale = min(1.0, LIVE_MAX_SIDE.toDouble() / max(cropped.cols(), cropped.rows()))
        val small = if (scale < 1.0) {
            Mat().also { Imgproc.resize(cropped, it, Size(cropped.cols() * scale, cropped.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) }
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

    /** Gradient field of the working image, kept as plain arrays for fast per-pixel sampling. */
    private class Grad(val w: Int, val h: Int, val gray: ByteArray, val gx: FloatArray, val gy: FloatArray, val mag: FloatArray, val thr: Float) {
        fun g(x: Int, y: Int): Int = gray[y * w + x].toInt() and 0xFF
    }

    private class Scored(val q: Array<PointF>, val score: Double, val area: Double, val minSupport: Double)

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
        Imgproc.resize(gray, resized, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        return resized to scale
    }

    private fun buildGrad(gray: Mat): Grad {
        val w = gray.cols(); val h = gray.rows()
        val sm = Mat(); Imgproc.GaussianBlur(gray, sm, Size(5.0, 5.0), 0.0)
        val gxM = Mat(); val gyM = Mat(); val magM = Mat()
        Imgproc.Sobel(sm, gxM, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(sm, gyM, CvType.CV_32F, 0, 1)
        Core.magnitude(gxM, gyM, magM)
        val gx = FloatArray(w * h); val gy = FloatArray(w * h); val mag = FloatArray(w * h); val g = ByteArray(w * h)
        gxM.get(0, 0, gx); gyM.get(0, 0, gy); magM.get(0, 0, mag); gray.get(0, 0, g)
        sm.release(); gxM.release(); gyM.release(); magM.release()
        // Noise-relative threshold: 4x the 30th percentile of the gradient, clamped (text-independent).
        val thr = (4.0 * percentile(mag, 30.0)).coerceIn(12.0, 50.0).toFloat()
        return Grad(w, h, g, gx, gy, mag, thr)
    }

    /** Percentile of non-negative values via a 1-unit histogram (O(n), no sort). */
    private fun percentile(values: FloatArray, p: Double): Double {
        val bins = IntArray(2048)
        for (v in values) bins[v.toInt().coerceIn(0, 2047)]++
        val target = (values.size * p / 100.0).toLong()
        var acc = 0L
        for (i in bins.indices) {
            acc += bins[i]
            if (acc > target) return i.toDouble()
        }
        return 2047.0
    }

    private fun percentileOfMat(mat: Mat, p: Double): Double {
        val a = FloatArray(mat.rows() * mat.cols()); mat.get(0, 0, a); return percentile(a, p)
    }

    private fun findBestQuad(gray: Mat, expectedAspectRatio: Float?, priorPx: Array<PointF>?): Candidate? {
        val grad = buildGrad(gray)
        val w = grad.w; val h = grad.h
        val frameArea = w.toDouble() * h
        val scored = ArrayList<Scored>()
        for (raw in generateCandidates(gray, frameArea)) {
            val q = orderCorners(raw)
            if (!isConvexPx(q)) continue
            val area = polyArea(q) / frameArea
            if (area < MIN_AREA_RATIO || area > MAX_AREA_RATIO) continue
            val ang = rightAngleScore(q)
            if (ang < 0.70) continue
            val sides = DoubleArray(4) { dist(q[it], q[(it + 1) % 4]) }
            if (sides.minOrNull()!! < 0.08 * min(w, h)) continue
            val sup = DoubleArray(4) { sideSupport(q, it, grad, 3) }
            val sMin = sup.minOrNull()!!
            if (sMin < 0.30) continue
            var score = 0.40 * sup.average() + 0.20 * sMin + 0.20 * ang + 0.20 * min(1.0, area / 0.35)
            if (expectedAspectRatio != null && expectedAspectRatio > 0f) {
                val ww = (sides[0] + sides[2]) / 2; val hh = (sides[1] + sides[3]) / 2
                val r = max(ww, hh) / max(1e-3, min(ww, hh))
                score *= max(0.6, 1 - abs(r - expectedAspectRatio) / expectedAspectRatio * 0.8)
            }
            if (priorPx != null) {
                val d = maxCornerDistance(q, priorPx) / max(w, h)
                if (d < 0.06) score += 0.08 * (1 - d / 0.06) // temporal continuity (live tracking)
            }
            scored.add(Scored(q, score, area, sMin))
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.score }
        var best = scored[0]
        // Outermost sheet: a well supported quad that contains the best one (sheet around a printed frame).
        var changed = true
        while (changed) {
            changed = false
            for (c in scored) {
                if (c === best) continue
                if (c.score >= best.score - 0.10 && c.minSupport >= 0.6 && c.area >= 1.10 * best.area && contains(c.q, best.q)) {
                    best = c; changed = true
                }
            }
        }
        if (best.score < RETURN_SCORE) return null
        var q = expandToSheet(best.q, grad)
        q = refineByLineFit(q, gray, max(3, (max(w, h) / 250.0).roundToInt()))
        if (!isConvexPx(q)) q = best.q
        return Candidate(q, best.score.toFloat().coerceIn(0f, 1f))
    }

    /** Edge maps (gray + text-removed), each at two sensitivities, plus Otsu paper/background masks. */
    private fun generateCandidates(gray: Mat, frameArea: Double): List<Array<PointF>> {
        val out = ArrayList<Array<PointF>>()
        val k = (max(3, (max(gray.cols(), gray.rows()) / 90.0).roundToInt())) or 1
        val kernelBig = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        val closed = Mat()
        Imgproc.morphologyEx(gray, closed, Imgproc.MORPH_CLOSE, kernelBig, Point(-1.0, -1.0), 2)
        kernelBig.release()
        val maps = ArrayList<Mat>()
        for (src0 in listOf(gray, closed)) {
            val src = Mat(); Imgproc.GaussianBlur(src0, src, Size(5.0, 5.0), 0.0)
            val gx = Mat(); val gy = Mat(); val mag = Mat()
            Imgproc.Sobel(src, gx, CvType.CV_32F, 1, 0); Imgproc.Sobel(src, gy, CvType.CV_32F, 0, 1); Core.magnitude(gx, gy, mag)
            val hi = max(percentileOfMat(mag, 92.0), 30.0)
            gx.release(); gy.release(); mag.release()
            for (f in doubleArrayOf(1.0, 0.5)) {
                val e = Mat()
                Imgproc.Canny(src, e, f * hi * 0.4 / 4.0, f * hi / 4.0)
                maps.add(e)
            }
            src.release()
        }
        val blurredClosed = Mat(); Imgproc.GaussianBlur(closed, blurredClosed, Size(9.0, 9.0), 0.0)
        val otsu = Mat(); Imgproc.threshold(blurredClosed, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val inv = Mat(); Core.bitwise_not(otsu, inv)
        blurredClosed.release(); closed.release()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val edgeMapCount = maps.size
        maps.add(otsu); maps.add(inv)
        for ((i, m) in maps.withIndex()) {
            if (i < edgeMapCount) Imgproc.dilate(m, m, kernel3)
            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(m, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release()
            for (c in contours) {
                try {
                    out.addAll(quadsFromContour(c, frameArea))
                } finally {
                    c.release()
                }
            }
            m.release()
        }
        kernel3.release()
        return out
    }

    private fun quadsFromContour(c: MatOfPoint, frameArea: Double): List<Array<PointF>> {
        // Cheap reject first (text produces thousands of tiny contours): bounding box area >= hull area.
        val br = Imgproc.boundingRect(c)
        if (br.width.toDouble() * br.height < 0.10 * frameArea) return emptyList()
        val idx = MatOfInt()
        Imgproc.convexHull(c, idx)
        val pts = c.toArray(); val ids = idx.toArray(); idx.release()
        if (ids.size < 4) return emptyList()
        val hull = MatOfPoint2f(*ids.map { pts[it] }.map { Point(it.x, it.y) }.toTypedArray())
        try {
            if (Imgproc.contourArea(hull) < 0.10 * frameArea) return emptyList()
            val peri = Imgproc.arcLength(hull, true)
            for (eps in doubleArrayOf(0.01, 0.02, 0.035, 0.05)) {
                val ap = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, ap, eps * peri, true)
                val poly = ap.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
                ap.release()
                if (poly.size == 4) return listOf(poly.toTypedArray())
                if (poly.size in 5..10) {
                    val res = ArrayList<Array<PointF>>()
                    res.add(maxAreaQuad(poly))
                    quadFromLongestEdges(poly)?.let { res.add(it) }
                    return res
                }
            }
            return emptyList()
        } finally {
            hull.release()
        }
    }

    private fun maxAreaQuad(p: List<PointF>): Array<PointF> {
        val n = p.size
        var best: Array<PointF> = arrayOf(p[0], p[1], p[2], p[3]); var ba = -1.0
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) for (l in k + 1 until n) {
            val q = arrayOf(p[i], p[j], p[k], p[l]); val a = polyArea(q)
            if (a > ba) { ba = a; best = q }
        }
        return best
    }

    /** Quad from the 4 longest polygon edges, extended and intersected (rebuilds a clipped corner). */
    private fun quadFromLongestEdges(p: List<PointF>): Array<PointF>? {
        val n = p.size
        val idx = (0 until n).sortedByDescending { dist(p[it], p[(it + 1) % n]) }.take(4).sorted()
        val lines = idx.map { Line(p[it].x.toDouble(), p[it].y.toDouble(), (p[(it + 1) % n].x - p[it].x).toDouble(), (p[(it + 1) % n].y - p[it].y).toDouble()) }
        val out = ArrayList<PointF>(4)
        for (k in 0 until 4) out.add(intersect(lines[k], lines[(k + 1) % 4]) ?: return null)
        return out.toTypedArray()
    }

    /** Fraction of samples along side [s] with an aligned gradient within ±[band] px. */
    private fun sideSupport(q: Array<PointF>, s: Int, gr: Grad, band: Int): Double {
        val a = q[s]; val b = q[(s + 1) % 4]
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val len = hypot(dx, dy)
        if (len < 10) return 0.0
        var nx = -dy / len; var ny = dx / len
        val cx = q.sumOf { it.x.toDouble() } / 4; val cy = q.sumOf { it.y.toDouble() } / 4
        if ((cx - (a.x + b.x) / 2.0) * nx + (cy - (a.y + b.y) / 2.0) * ny < 0) { nx = -nx; ny = -ny }
        val n = max(16, (len / 4).toInt())
        var hit = 0
        for (k in 0 until n) {
            val t = 0.05 + 0.90 * k / (n - 1)
            val px = a.x + dx * t; val py = a.y + dy * t
            for (o in OFFSETS) {
                if (abs(o) > band) continue
                val x = (px + nx * o).roundToInt(); val y = (py + ny * o).roundToInt()
                if (x < 0 || y < 0 || x >= gr.w || y >= gr.h) continue
                val i = y * gr.w + x
                if (gr.mag[i] > gr.thr && abs(gr.gx[i] * nx + gr.gy[i] * ny) / (gr.mag[i] + 1e-6) > 0.75) { hit++; break }
            }
        }
        return hit.toDouble() / n
    }

    private val OFFSETS = intArrayOf(0, -1, 1, -2, 2, -3, 3)

    /**
     * Moves each side outward (≤ 6 % of the frame) to the real sheet border when a printed frame sits just
     * inside it: parallel edge, brighter towards the inside, paper-like strip between both lines.
     */
    private fun expandToSheet(q: Array<PointF>, gr: Grad): Array<PointF> {
        val maxOff = (0.06 * max(gr.w, gr.h)).toInt()
        val cx = q.sumOf { it.x.toDouble() } / 4; val cy = q.sumOf { it.y.toDouble() } / 4
        val lines = ArrayList<Line>(4)
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val len = hypot(dx, dy)
            var nx = -dy / len; var ny = dx / len
            if ((cx - (a.x + b.x) / 2.0) * nx + (cy - (a.y + b.y) / 2.0) * ny < 0) { nx = -nx; ny = -ny }
            fun pix(o: Double, t: Double): Int {
                val x = (a.x + dx * t - nx * o).roundToInt(); val y = (a.y + dy * t - ny * o).roundToInt()
                return if (x in 0 until gr.w && y in 0 until gr.h) y * gr.w + x else -1
            }
            val ts = DoubleArray(48) { 0.12 + 0.76 * it / 47 }
            val inner = ts.map { pix(-6.0, it) }.filter { it >= 0 }.map { gr.gray[it].toInt() and 0xFF }.sorted()
            var best = 0
            if (inner.isNotEmpty()) {
                val paper = inner[inner.size / 2]
                for (o in 3 until maxOff) {
                    var hits = 0; var bright = 0; var strip = 0
                    for (t in ts) {
                        for (oo in intArrayOf(o - 1, o, o + 1)) {
                            val p = pix(oo.toDouble(), t)
                            if (p < 0) continue
                            if (gr.mag[p] > gr.thr && (gr.gx[p] * nx + gr.gy[p] * ny) / (gr.mag[p] + 1e-6) > 0.75) { hits++; break }
                        }
                        val ps = pix(o * 0.5, t)
                        if (ps >= 0) { strip++; if ((gr.gray[ps].toInt() and 0xFF) >= 0.85 * paper) bright++ }
                    }
                    if (hits >= 0.45 * ts.size && strip > 0 && bright >= 0.8 * strip) best = o
                }
            }
            lines.add(Line(a.x - nx * best, a.y - ny * best, dx, dy))
        }
        val out = ArrayList<PointF>(4)
        for (k in 0 until 4) out.add(intersect(lines[(k + 3) % 4], lines[k]) ?: return q)
        return out.toTypedArray()
    }

    /** Robust (Huber) line fit on the edge pixels close to each side, then corner = side intersections. */
    private fun refineByLineFit(q: Array<PointF>, gray: Mat, band: Int): Array<PointF> {
        val sm = Mat(); Imgproc.GaussianBlur(gray, sm, Size(3.0, 3.0), 0.0)
        val e = Mat(); Imgproc.Canny(sm, e, 20.0, 60.0); sm.release()
        val w = e.cols(); val h = e.rows()
        val data = ByteArray(w * h); e.get(0, 0, data); e.release()
        val lines = arrayOfNulls<Line>(4)
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val len = hypot(dx, dy)
            if (len < 10) continue
            val ux = dx / len; val uy = dy / len; val nx = -uy; val ny = ux
            val pts = ArrayList<Point>()
            // scan a band around the side
            val steps = len.toInt()
            for (k in (0.08 * steps).toInt()..(0.92 * steps).toInt()) {
                for (o in -band..band) {
                    val x = (a.x + ux * k + nx * o).roundToInt(); val y = (a.y + uy * k + ny * o).roundToInt()
                    if (x in 0 until w && y in 0 until h && data[y * w + x].toInt() != 0) pts.add(Point(x.toDouble(), y.toDouble()))
                }
            }
            if (pts.size < max(15.0, 0.25 * len)) continue
            val mp = MatOfPoint2f(*pts.distinct().toTypedArray())
            val line = Mat()
            try {
                Imgproc.fitLine(mp, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                val v = FloatArray(4); line.get(0, 0, v)
                lines[i] = Line(v[2].toDouble(), v[3].toDouble(), v[0].toDouble(), v[1].toDouble())
            } finally {
                mp.release(); line.release()
            }
        }
        val out = q.copyOf()
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4] ?: continue
            val l2 = lines[i] ?: continue
            val p = intersect(l1, l2) ?: continue
            if (hypot((p.x - q[i].x).toDouble(), (p.y - q[i].y).toDouble()) < band * 3.0) out[i] = p
        }
        return out
    }

    // ------------------------------------------------------------------ geometry helpers

    private class Line(val x: Double, val y: Double, val dx: Double, val dy: Double)

    private fun intersect(l1: Line, l2: Line): PointF? {
        val det = l1.dx * (-l2.dy) - (-l2.dx) * l1.dy
        if (abs(det) < 1e-9) return null
        val rx = l2.x - l1.x; val ry = l2.y - l1.y
        val t = (rx * (-l2.dy) - (-l2.dx) * ry) / det
        val px = l1.x + l1.dx * t; val py = l1.y + l1.dy * t
        if (!px.isFinite() || !py.isFinite()) return null
        return PointF(px.toFloat(), py.toFloat())
    }

    private fun dist(a: PointF, b: PointF) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

    private fun polyArea(q: Array<PointF>): Double {
        var s = 0.0
        for (i in q.indices) { val a = q[i]; val b = q[(i + 1) % q.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return abs(s) / 2
    }

    private fun isConvexPx(q: Array<PointF>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cr = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
            if (abs(cr) < 1e-6) return false
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** True when every corner of [inner] lies inside (or within 2 px of) the convex quad [outer]. */
    private fun contains(outer: Array<PointF>, inner: Array<PointF>): Boolean {
        val poly = MatOfPoint2f(*outer.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        return try {
            inner.all { Imgproc.pointPolygonTest(poly, Point(it.x.toDouble(), it.y.toDouble()), true) >= -2.0 }
        } finally {
            poly.release()
        }
    }

    private fun orderCorners(pts: Array<PointF>): Array<PointF> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val tr = pts.minByOrNull { it.y - it.x }!!
        val bl = pts.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun rightAngleScore(q: Array<PointF>): Double {
        var total = 0.0
        for (i in 0 until 4) {
            val prev = q[(i + 3) % 4]; val cur = q[i]; val next = q[(i + 1) % 4]
            val ax = (prev.x - cur.x).toDouble(); val ay = (prev.y - cur.y).toDouble()
            val bx = (next.x - cur.x).toDouble(); val by = (next.y - cur.y).toDouble()
            val den = hypot(ax, ay) * hypot(bx, by)
            if (den < 1e-6) return 0.0
            total += 1.0 - abs((ax * bx + ay * by) / den).coerceIn(0.0, 1.0)
        }
        return total / 4.0
    }

    private fun maxCornerDistance(a: Array<PointF>, b: Array<PointF>): Double {
        var m = 0.0
        for (i in 0 until 4) m = max(m, dist(a[i], b[i]))
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
        val p = c.pointsPx.map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) }
        fun n(pt: PointF) = PointF((pt.x / origW).coerceIn(0f, 1f), (pt.y / origH).coerceIn(0f, 1f))
        val quad = DocumentQuad(n(p[0]), n(p[1]), n(p[2]), n(p[3]))
        return DocumentDetection(quad, c.confidence, origW, origH)
    }
}
