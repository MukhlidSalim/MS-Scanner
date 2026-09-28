package com.example.engine.cv

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Result of document detection.
 * [quad] is normalized (0..1) to an image of [imageWidth] x [imageHeight] (the frame that was analysed,
 * already in upright/oriented form). [confidence] is 0..1.
 */
data class DocumentDetection(
    val quad: DocumentQuad,
    val confidence: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * The single, authoritative document border detector used by live camera analysis, still captures,
 * imports, batch processing and the crop editor.
 *
 * Algorithm (pure Kotlin, no extra libraries):
 *  1. Luma at a bounded working resolution (320 px live, 640 px still).
 *  2. 5x5 Gaussian blur -> Sobel gradients -> non-maximum suppression -> hysteresis (Canny-style)
 *     with thresholds derived from the image's own edge-strength distribution.
 *  3. Candidate regions from FOUR independent sources: thin edges, bridged (dilated) edges, and the
 *     bright / dark sides of an Otsu segmentation. This covers documents with weak borders, documents
 *     whose border touches background texture, and light-on-light / dark-on-dark scenes.
 *  4. For each candidate component: convex hull -> maximum-area inscribed quadrilateral.
 *  5. Sub-pixel refinement: each side is re-fitted (total least squares) to the edge pixels that lie
 *     in a narrow band around it AND whose gradient is perpendicular to it; corners are the
 *     intersections of adjacent fitted lines.
 *  6. Scoring: per-side edge support with gradient-direction agreement (weakest side counts),
 *     right-angle score, relative area, and optional expected aspect ratio (ID card / passport).
 *
 * No fixed quad or bounding-box approximation is ever returned: when nothing qualifies, the result
 * is null and callers fall back to manual crop.
 */
object DocumentDetector {

    /** Minimum confidence for a detection to be reported at all. */
    const val MIN_CONFIDENCE = 0.60f

    /** Confidence required before live auto-capture may arm. */
    const val AUTO_CAPTURE_CONFIDENCE = 0.70f

    private const val LIVE_MAX_SIDE = 320
    private const val STILL_MAX_SIDE = 640
    private const val MAX_HULL_VERTICES = 32

    // ------------------------------------------------------------------ public entry points

    /** Detect on an upright bitmap (EXIF already applied). Thread-safe, CPU bound: call off the main thread. */
    fun detect(bitmap: Bitmap, expectedAspectRatio: Float? = null): DocumentDetection? {
        val gray = GrayImage.fromBitmap(bitmap, STILL_MAX_SIDE) ?: return null
        val best = analyze(gray, expectedAspectRatio).firstOrNull() ?: return null
        if (best.confidence < MIN_CONFIDENCE) return null
        return DocumentDetection(best.quad, best.confidence, bitmap.width, bitmap.height)
    }

    /**
     * Still-image detection guided by a prior quad (e.g. the stable live-preview quad at the moment
     * of capture, in the same normalized frame). Preference order:
     *  1. a detected candidate that agrees with the prior (sub-pixel accurate on the full-res still),
     *  2. the prior itself refined to the still's edges when it is still supported by them,
     *  3. the best independent detection,
     *  4. the prior as-is (it was stable in preview; the user saw it).
     */
    fun detectWithPrior(bitmap: Bitmap, prior: DocumentQuad?, expectedAspectRatio: Float? = null): DocumentDetection? {
        if (prior == null) return detect(bitmap, expectedAspectRatio)
        val gray = GrayImage.fromBitmap(bitmap, STILL_MAX_SIDE)
            ?: return DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height)
        val candidates = analyze(gray, expectedAspectRatio)
        val agreeing = candidates
            .filter { it.confidence >= 0.5f && it.quad.maxCornerDistance(prior) < 0.05f }
            .maxByOrNull { it.confidence }
        if (agreeing != null) return DocumentDetection(agreeing.quad, agreeing.confidence, bitmap.width, bitmap.height)

        val edges = EdgeMap.build(gray)
        val priorPx = toPixels(prior, gray.width, gray.height)
        val refined = orderPx(refine(priorPx, edges))
        val refinedScore = evaluate(refined, edges, expectedAspectRatio)
        if (refinedScore != null && refinedScore >= 0.5f) {
            return DocumentDetection(toNormalized(refined, gray.width, gray.height), refinedScore, bitmap.width, bitmap.height)
        }
        val best = candidates.firstOrNull()
        if (best != null && best.confidence >= MIN_CONFIDENCE) {
            return DocumentDetection(best.quad, best.confidence, bitmap.width, bitmap.height)
        }
        return DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height)
    }

    /**
     * CameraX fast path. Reads only the Y plane inside [ImageProxy.getCropRect] (which, with a ViewPort
     * bound in a UseCaseGroup, is exactly the region visible in the PreviewView), detects, and returns
     * the quad in the ORIENTED frame (rotationDegrees applied), sized cropW x cropH (swapped for 90/270).
     */
    fun detectFromImageProxy(imageProxy: ImageProxy, expectedAspectRatio: Float? = null): DocumentDetection? {
        val gray = GrayImage.fromImageProxy(imageProxy, LIVE_MAX_SIDE) ?: return null
        val best = analyze(gray, expectedAspectRatio).firstOrNull() ?: return null
        if (best.confidence < MIN_CONFIDENCE) return null
        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val crop = imageProxy.cropRect
        val (ow, oh) = if (rotation == 90 || rotation == 270) crop.height() to crop.width() else crop.width() to crop.height()
        return DocumentDetection(best.quad.rotated(rotation).sortCorners(), best.confidence, ow, oh)
    }

    /** Geometric plausibility of a normalized quad (used to validate stored or user-edited quads). */
    fun isPlausible(quad: DocumentQuad): Boolean {
        if (quad.points().any { !it.x.isFinite() || !it.y.isFinite() || it.x !in -0.02f..1.02f || it.y !in -0.02f..1.02f }) return false
        if (!quad.isConvex()) return false
        val area = quad.area()
        if (area < 0.02f || area > 1.0001f) return false
        return quad.minSide() >= 0.03f
    }

    // ------------------------------------------------------------------ core analysis

    private class Candidate(val quad: DocumentQuad, val confidence: Float)

    /** Returns evaluated candidates sorted by confidence (highest first). */
    private fun analyze(gray: GrayImage, expectedAspectRatio: Float?): List<Candidate> {
        val w = gray.width
        val h = gray.height
        if (w < 32 || h < 32) return emptyList()
        val edges = EdgeMap.build(gray)
        if (edges.edgePixelCount < 50) return emptyList()

        val otsu = otsuThreshold(edges.blurred)
        val masks = listOf(
            edges.edge,
            dilate(edges.edge, w, h),
            BooleanArray(w * h) { edges.blurred[it] >= otsu },
            BooleanArray(w * h) { edges.blurred[it] < otsu }
        )

        val rawQuads = ArrayList<FloatArray>()
        val labeler = ComponentLabeler(w, h)
        for (mask in masks) {
            labeler.forEachComponent(mask) { minX, minY, maxX, maxY, count, hullPoints ->
                if (maxX - minX >= 0.2f * w && maxY - minY >= 0.2f * h && count >= 40) {
                    val hull = convexHull(hullPoints)
                    if (hull.size >= 4) {
                        val simplified = simplifyHull(hull, MAX_HULL_VERTICES)
                        maxAreaQuad(simplified)?.let { rawQuads.add(orderPx(it)) }
                    }
                }
            }
        }

        val results = ArrayList<Candidate>()
        for (q in rawQuads) {
            val refined = orderPx(refine(q, edges))
            var bestPx: FloatArray? = null
            var bestScore = -1f
            for (option in arrayOf(refined, q)) {
                val s = evaluate(option, edges, expectedAspectRatio) ?: continue
                if (s > bestScore) {
                    bestScore = s
                    bestPx = option
                }
            }
            if (bestPx != null) results.add(Candidate(toNormalized(bestPx, w, h), bestScore))
        }
        results.sortByDescending { it.confidence }
        return results
    }

    // ------------------------------------------------------------------ grayscale input

    private class GrayImage(val data: FloatArray, val width: Int, val height: Int) {
        companion object {
            fun fromBitmap(bitmap: Bitmap, maxSide: Int): GrayImage? {
                if (bitmap.width < 32 || bitmap.height < 32) return null
                val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
                val w = (bitmap.width * scale).roundToInt().coerceAtLeast(32)
                val h = (bitmap.height * scale).roundToInt().coerceAtLeast(32)
                val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
                try {
                    val px = IntArray(w * h)
                    scaled.getPixels(px, 0, w, 0, 0, w, h)
                    val out = FloatArray(w * h)
                    for (i in px.indices) {
                        val p = px[i]
                        out[i] = 0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)
                    }
                    return GrayImage(out, w, h)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }

            fun fromImageProxy(imageProxy: ImageProxy, maxSide: Int): GrayImage? {
                val plane = imageProxy.planes.firstOrNull() ?: return null
                val crop = imageProxy.cropRect
                val srcW = crop.width()
                val srcH = crop.height()
                if (srcW < 32 || srcH < 32) return null
                val scale = min(1f, maxSide.toFloat() / max(srcW, srcH))
                val w = (srcW * scale).roundToInt().coerceAtLeast(32)
                val h = (srcH * scale).roundToInt().coerceAtLeast(32)
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val limit = buffer.limit()
                val out = FloatArray(w * h)
                // 2x2 box sampling per output pixel to reduce aliasing of the downscale.
                val stepX = srcW.toFloat() / w
                val stepY = srcH.toFloat() / h
                for (y in 0 until h) {
                    val sy0 = crop.top + (y * stepY).toInt().coerceIn(0, srcH - 1)
                    val sy1 = crop.top + ((y + 0.5f) * stepY).toInt().coerceIn(0, srcH - 1)
                    for (x in 0 until w) {
                        val sx0 = crop.left + (x * stepX).toInt().coerceIn(0, srcW - 1)
                        val sx1 = crop.left + ((x + 0.5f) * stepX).toInt().coerceIn(0, srcW - 1)
                        var sum = 0
                        var n = 0
                        val i00 = sy0 * rowStride + sx0 * pixelStride
                        val i01 = sy0 * rowStride + sx1 * pixelStride
                        val i10 = sy1 * rowStride + sx0 * pixelStride
                        val i11 = sy1 * rowStride + sx1 * pixelStride
                        if (i00 in 0 until limit) { sum += buffer.get(i00).toInt() and 0xFF; n++ }
                        if (i01 in 0 until limit) { sum += buffer.get(i01).toInt() and 0xFF; n++ }
                        if (i10 in 0 until limit) { sum += buffer.get(i10).toInt() and 0xFF; n++ }
                        if (i11 in 0 until limit) { sum += buffer.get(i11).toInt() and 0xFF; n++ }
                        out[y * w + x] = if (n == 0) 127f else sum.toFloat() / n
                    }
                }
                return GrayImage(out, w, h)
            }
        }
    }

    // ------------------------------------------------------------------ edges

    private class EdgeMap(
        val width: Int,
        val height: Int,
        val blurred: FloatArray,
        val gx: FloatArray,
        val gy: FloatArray,
        val edge: BooleanArray,
        val edgeIndices: IntArray,
        val edgePixelCount: Int
    ) {
        companion object {
            fun build(gray: GrayImage): EdgeMap {
                val w = gray.width
                val h = gray.height
                val blurred = DocumentDetector.gaussianBlur5(gray.data, w, h)
                val gx = FloatArray(w * h)
                val gy = FloatArray(w * h)
                val mag = FloatArray(w * h)
                for (y in 1 until h - 1) {
                    val r = y * w
                    val p = r - w
                    val n = r + w
                    for (x in 1 until w - 1) {
                        val sx = (blurred[p + x + 1] + 2f * blurred[r + x + 1] + blurred[n + x + 1]) -
                            (blurred[p + x - 1] + 2f * blurred[r + x - 1] + blurred[n + x - 1])
                        val sy = (blurred[n + x - 1] + 2f * blurred[n + x] + blurred[n + x + 1]) -
                            (blurred[p + x - 1] + 2f * blurred[p + x] + blurred[p + x + 1])
                        gx[r + x] = sx
                        gy[r + x] = sy
                        mag[r + x] = sqrt(sx * sx + sy * sy)
                    }
                }
                // Non-maximum suppression.
                val thin = FloatArray(w * h)
                var maxMag = 0f
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val i = y * w + x
                        val m = mag[i]
                        if (m <= 0f) continue
                        val ax = abs(gx[i])
                        val ay = abs(gy[i])
                        val n1: Float
                        val n2: Float
                        if (ay <= ax * 0.4142f) {
                            n1 = mag[i - 1]; n2 = mag[i + 1]
                        } else if (ax <= ay * 0.4142f) {
                            n1 = mag[i - w]; n2 = mag[i + w]
                        } else if ((gx[i] > 0f) == (gy[i] > 0f)) {
                            n1 = mag[i - w - 1]; n2 = mag[i + w + 1]
                        } else {
                            n1 = mag[i - w + 1]; n2 = mag[i + w - 1]
                        }
                        if (m >= n1 && m >= n2) {
                            thin[i] = m
                            if (m > maxMag) maxMag = m
                        }
                    }
                }
                val edge = BooleanArray(w * h)
                if (maxMag <= 0f) return EdgeMap(w, h, blurred, gx, gy, edge, IntArray(0), 0)

                // Adaptive hysteresis thresholds from the distribution of thin-edge strengths.
                val bins = 1024
                val hist = IntArray(bins)
                var nz = 0
                for (v in thin) if (v > 0f) {
                    hist[((v / maxMag) * (bins - 1)).toInt()]++
                    nz++
                }
                val target = (nz * 0.85f).toInt()
                var acc = 0
                var hiBin = bins - 1
                for (b in 0 until bins) {
                    acc += hist[b]
                    if (acc >= target) { hiBin = b; break }
                }
                val hi = max(hiBin.toFloat() / (bins - 1) * maxMag, 20f)
                val lo = hi * 0.4f

                val stack = IntArray(w * h)
                var sp = 0
                for (i in thin.indices) if (thin[i] >= hi) { edge[i] = true; stack[sp++] = i }
                while (sp > 0) {
                    val i = stack[--sp]
                    val x = i % w
                    val y = i / w
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val j = ny * w + nx
                            if (!edge[j] && thin[j] >= lo) { edge[j] = true; stack[sp++] = j }
                        }
                    }
                }
                var count = 0
                for (e in edge) if (e) count++
                val idx = IntArray(count)
                var k = 0
                for (i in edge.indices) if (edge[i]) idx[k++] = i
                return EdgeMap(w, h, blurred, gx, gy, edge, idx, count)
            }
        }
    }

    private fun gaussianBlur5(src: FloatArray, w: Int, h: Int): FloatArray {
        val k0 = 6f / 16f; val k1 = 4f / 16f; val k2 = 1f / 16f
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val xm2 = r + max(x - 2, 0); val xm1 = r + max(x - 1, 0)
                val xp1 = r + min(x + 1, w - 1); val xp2 = r + min(x + 2, w - 1)
                tmp[r + x] = k2 * src[xm2] + k1 * src[xm1] + k0 * src[r + x] + k1 * src[xp1] + k2 * src[xp2]
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ym2 = max(y - 2, 0) * w; val ym1 = max(y - 1, 0) * w
            val yp1 = min(y + 1, h - 1) * w; val yp2 = min(y + 2, h - 1) * w
            val r = y * w
            for (x in 0 until w) {
                out[r + x] = k2 * tmp[ym2 + x] + k1 * tmp[ym1 + x] + k0 * tmp[r + x] + k1 * tmp[yp1 + x] + k2 * tmp[yp2 + x]
            }
        }
        return out
    }

    private fun dilate(input: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(input.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!input[y * w + x]) continue
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx in 0 until w) out[ny * w + nx] = true
                    }
                }
            }
        }
        return out
    }

    private fun otsuThreshold(values: FloatArray): Float {
        val hist = IntArray(256)
        for (v in values) hist[v.toInt().coerceIn(0, 255)]++
        val total = values.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 128
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = i }
        }
        return threshold + 0.5f
    }

    // ------------------------------------------------------------------ components & hull

    /**
     * 8-connected component labeling that reports, per component, the bounding box, pixel count
     * and the left/right extreme pixel of every row (the convex hull of a component equals the hull
     * of these row extremes, which keeps hull input O(height)).
     */
    private class ComponentLabeler(private val w: Int, private val h: Int) {
        private val visited = BooleanArray(w * h)
        private val queue = IntArray(w * h)
        private val rowMin = IntArray(h) { Int.MAX_VALUE }
        private val rowMax = IntArray(h) { -1 }
        private val touchedRows = IntArray(h)

        fun forEachComponent(
            mask: BooleanArray,
            onComponent: (minX: Int, minY: Int, maxX: Int, maxY: Int, count: Int, hullPoints: LongArray) -> Unit
        ) {
            java.util.Arrays.fill(visited, false)
            for (start in mask.indices) {
                if (!mask[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var minX = w; var minY = h; var maxX = -1; var maxY = -1
                var count = 0
                var touched = 0
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % w
                    val y = i / w
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (rowMax[y] < 0) touchedRows[touched++] = y
                    if (x < rowMin[y]) rowMin[y] = x
                    if (x > rowMax[y]) rowMax[y] = x
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val j = ny * w + nx
                            if (mask[j] && !visited[j]) {
                                visited[j] = true
                                queue[tail++] = j
                            }
                        }
                    }
                }
                val pts = LongArray(touched * 2)
                for (t in 0 until touched) {
                    val y = touchedRows[t]
                    pts[2 * t] = DocumentDetector.pack(rowMin[y], y)
                    pts[2 * t + 1] = DocumentDetector.pack(rowMax[y], y)
                    rowMin[y] = Int.MAX_VALUE
                    rowMax[y] = -1
                }
                onComponent(minX, minY, maxX, maxY, count, pts)
            }
        }
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 20) or y.toLong()
    private fun px(p: Long): Float = (p ushr 20).toFloat()
    private fun py(p: Long): Float = (p and 0xFFFFF).toFloat()

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    /** Andrew's monotone chain. Returns hull as flat [x0,y0,x1,y1,...] in CCW order (math orientation). */
    private fun convexHull(points: LongArray): List<PointF> {
        val sorted = points.distinct().sorted()
        if (sorted.size < 3) return sorted.map { PointF(px(it), py(it)) }
        val hull = ArrayList<PointF>(sorted.size * 2)
        for (p in sorted) {
            val x = px(p); val y = py(p)
            while (hull.size >= 2 && cross(hull[hull.size - 2].x, hull[hull.size - 2].y, hull[hull.size - 1].x, hull[hull.size - 1].y, x, y) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(PointF(x, y))
        }
        val lowerSize = hull.size + 1
        for (k in sorted.size - 2 downTo 0) {
            val p = sorted[k]
            val x = px(p); val y = py(p)
            while (hull.size >= lowerSize && cross(hull[hull.size - 2].x, hull[hull.size - 2].y, hull[hull.size - 1].x, hull[hull.size - 1].y, x, y) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(PointF(x, y))
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    /** Removes the vertex contributing the least area until at most [maxVertices] remain. */
    private fun simplifyHull(hull: List<PointF>, maxVertices: Int): List<PointF> {
        val pts = ArrayList(hull)
        while (pts.size > maxVertices) {
            var bestIdx = 0
            var bestArea = Float.MAX_VALUE
            val n = pts.size
            for (i in 0 until n) {
                val a = pts[(i - 1 + n) % n]; val b = pts[i]; val c = pts[(i + 1) % n]
                val area = abs(cross(a.x, a.y, b.x, b.y, c.x, c.y))
                if (area < bestArea) { bestArea = area; bestIdx = i }
            }
            pts.removeAt(bestIdx)
        }
        return pts
    }

    /** Maximum-area quadrilateral with vertices on the hull. O(n^3) with n <= 32. */
    private fun maxAreaQuad(h: List<PointF>): FloatArray? {
        val n = h.size
        if (n < 4) return null
        fun tri(a: PointF, b: PointF, c: PointF) = abs(cross(a.x, a.y, b.x, b.y, c.x, c.y)) * 0.5f
        var best = -1f
        var result: FloatArray? = null
        for (i in 0 until n) {
            for (k in i + 2 until n) {
                var bj = -1; var bjA = -1f
                for (j in i + 1 until k) {
                    val a = tri(h[i], h[j], h[k])
                    if (a > bjA) { bjA = a; bj = j }
                }
                var bl = -1; var blA = -1f
                for (l in k + 1 until i + n) {
                    val a = tri(h[i], h[k], h[l % n])
                    if (a > blA) { blA = a; bl = l % n }
                }
                if (bj < 0 || bl < 0 || i == bl) continue
                if (bjA + blA > best) {
                    best = bjA + blA
                    result = floatArrayOf(h[i].x, h[i].y, h[bj].x, h[bj].y, h[k].x, h[k].y, h[bl].x, h[bl].y)
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------ quad helpers (pixel space)

    /** Orders 4 pixel-space points TL, TR, BR, BL. */
    private fun orderPx(q: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val idx = (0 until 4).sortedBy { atan2((q[2 * it + 1] - cy).toDouble(), (q[2 * it] - cx).toDouble()) }
        var tl = 0
        var minSum = Float.MAX_VALUE
        for (k in 0 until 4) {
            val s = q[2 * idx[k]] + q[2 * idx[k] + 1]
            if (s < minSum) { minSum = s; tl = k }
        }
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val src = idx[(tl + k) % 4]
            out[2 * k] = q[2 * src]
            out[2 * k + 1] = q[2 * src + 1]
        }
        return out
    }

    private fun toNormalized(q: FloatArray, w: Int, h: Int): DocumentQuad {
        fun p(i: Int) = PointF(((q[2 * i] + 0.5f) / w).coerceIn(0f, 1f), ((q[2 * i + 1] + 0.5f) / h).coerceIn(0f, 1f))
        return DocumentQuad(p(0), p(1), p(2), p(3))
    }

    private fun toPixels(quad: DocumentQuad, w: Int, h: Int): FloatArray {
        val s = quad.sortCorners().points()
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = s[i].x * w - 0.5f
            out[2 * i + 1] = s[i].y * h - 0.5f
        }
        return out
    }

    /**
     * Re-fits every side to the aligned edge pixels in a +-3 px band (inner 80% of the side), then
     * intersects adjacent lines. A side with too little evidence keeps its original corners.
     */
    private fun refine(q: FloatArray, edges: EdgeMap, band: Float = 3f): FloatArray {
        val w = edges.width
        val maxShift = 0.08f * max(edges.width, edges.height)
        val lines = arrayOfNulls<FloatArray>(4) // [cx, cy, dx, dy]
        for (s in 0 until 4) {
            val ax = q[2 * s]; val ay = q[2 * s + 1]
            val bx = q[2 * ((s + 1) % 4)]; val by = q[2 * ((s + 1) % 4) + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 8f) return q
            val ux = (bx - ax) / len; val uy = (by - ay) / len
            val nx = -uy; val ny = ux
            var n = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (i in edges.edgeIndices) {
                val x = (i % w).toFloat(); val y = (i / w).toFloat()
                val rx = x - ax; val ry = y - ay
                val t = rx * ux + ry * uy
                if (t < 0.1f * len || t > 0.9f * len) continue
                if (abs(rx * nx + ry * ny) > band) continue
                val gxv = edges.gx[i]; val gyv = edges.gy[i]
                val gm = hypot(gxv, gyv) + 1e-6f
                if (abs(gxv * nx + gyv * ny) / gm < 0.8f) continue
                n++
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
            }
            if (n < max(8f, 0.2f * len)) continue
            val mx = sx / n; val my = sy / n
            val cxx = sxx / n - mx * mx; val cyy = syy / n - my * my; val cxy = sxy / n - mx * my
            val theta = 0.5 * atan2(2 * cxy, cxx - cyy)
            lines[s] = floatArrayOf(mx.toFloat(), my.toFloat(), cos(theta).toFloat(), sin(theta).toFloat())
        }
        val out = q.copyOf()
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4] ?: continue
            val l2 = lines[i] ?: continue
            val den = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(den) < 1e-4f) continue
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
            val ix = l1[0] + t * l1[2]; val iy = l1[1] + t * l1[3]
            if (hypot(ix - q[2 * i], iy - q[2 * i + 1]) > maxShift) continue
            out[2 * i] = ix
            out[2 * i + 1] = iy
        }
        return out
    }

    /** Returns confidence 0..1 or null when the quad is not a plausible document. */
    private fun evaluate(q: FloatArray, edges: EdgeMap, expectedAspectRatio: Float?): Float? {
        val w = edges.width; val h = edges.height
        // Convexity
        var sign = 0
        for (i in 0 until 4) {
            val a = 2 * i; val b = 2 * ((i + 1) % 4); val c = 2 * ((i + 2) % 4)
            val cr = cross(q[a], q[a + 1], q[b], q[b + 1], q[c], q[c + 1])
            if (abs(cr) < 1e-6f) return null
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return null
        }
        var area2 = 0f
        for (i in 0 until 4) {
            val a = 2 * i; val b = 2 * ((i + 1) % 4)
            area2 += q[a] * q[b + 1] - q[b] * q[a + 1]
        }
        val relArea = abs(area2) / 2f / (w.toFloat() * h)
        if (relArea < 0.05f || relArea > 0.995f) return null

        val sides = FloatArray(4) { i -> hypot(q[2 * ((i + 1) % 4)] - q[2 * i], q[2 * ((i + 1) % 4) + 1] - q[2 * i + 1]) }
        if (minOf(minOf(sides[0], sides[1]), minOf(sides[2], sides[3])) < 0.08f * min(w, h)) return null
        if (min(sides[0], sides[2]) / max(sides[0], sides[2]) < 0.4f) return null
        if (min(sides[1], sides[3]) / max(sides[1], sides[3]) < 0.4f) return null

        var supportSum = 0f
        var supportMin = 1f
        for (s in 0 until 4) {
            val v = sideSupport(q, s, edges)
            supportSum += v
            supportMin = min(supportMin, v)
        }
        val supportMean = supportSum / 4f
        if (supportMin < 0.35f || supportMean < 0.55f) return null

        val angle = rightAngleScore(q)
        if (angle < 0.75f) return null

        var conf = 0.45f * supportMean + 0.2f * supportMin + 0.2f * angle + 0.15f * min(1f, relArea / 0.25f)
        if (expectedAspectRatio != null && expectedAspectRatio > 0f) {
            val sw = (sides[0] + sides[2]) / 2f
            val sh = (sides[1] + sides[3]) / 2f
            val ratio = max(sw, sh) / max(1e-3f, min(sw, sh))
            val dev = abs(ratio - expectedAspectRatio) / expectedAspectRatio
            conf *= max(0.6f, 1f - dev * 0.8f)
        }
        return conf.coerceIn(0f, 1f)
    }

    private fun sideSupport(q: FloatArray, s: Int, edges: EdgeMap): Float {
        val w = edges.width; val h = edges.height
        val ax = q[2 * s]; val ay = q[2 * s + 1]
        val bx = q[2 * ((s + 1) % 4)]; val by = q[2 * ((s + 1) % 4) + 1]
        val dx = bx - ax; val dy = by - ay
        val len = max(hypot(dx, dy), 1e-3f)
        val nx = -dy / len; val ny = dx / len
        val samples = max(12, (len / 3f).toInt())
        var hit = 0
        for (k in 0 until samples) {
            val t = (k + 0.5f) / samples
            val cx = ax + dx * t; val cy = ay + dy * t
            for (o in -2..2) {
                val x = (cx + nx * o).roundToInt(); val y = (cy + ny * o).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val i = y * w + x
                if (!edges.edge[i]) continue
                val gxv = edges.gx[i]; val gyv = edges.gy[i]
                val gm = hypot(gxv, gyv) + 1e-6f
                if (abs(gxv * nx + gyv * ny) / gm > 0.7f) { hit++; break }
            }
        }
        return hit.toFloat() / samples
    }

    private fun rightAngleScore(q: FloatArray): Float {
        var total = 0f
        for (i in 0 until 4) {
            val p = 2 * ((i + 3) % 4); val c = 2 * i; val n = 2 * ((i + 1) % 4)
            val ax = q[p] - q[c]; val ay = q[p + 1] - q[c + 1]
            val bx = q[n] - q[c]; val by = q[n + 1] - q[c + 1]
            val den = hypot(ax, ay) * hypot(bx, by)
            if (den < 1e-6f) return 0f
            total += 1f - abs((ax * bx + ay * by) / den).coerceIn(0f, 1f)
        }
        return total / 4f
    }
}
