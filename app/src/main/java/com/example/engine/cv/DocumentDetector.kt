package com.example.engine.cv

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
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
 * The single document border detector (live camera, still capture, gallery import, re-detect, editor).
 *
 * Root causes fixed in this version (measured on the reported scene and on synthetic scenes):
 *  1. The only edge map used a hysteresis threshold at the 85th percentile of ALL edge strengths. On a
 *     text-dense form the percentile is set by printed text/tables, so the paper-to-desk border (white on a
 *     light desk) fell below the LOW threshold and vanished (0 % of the top border in the edge map).
 *     -> A second, noise-relative LOW threshold map carries the border evidence.
 *  2. Candidates came only from connected components -> hull -> max-area quad. A broken border yields no
 *     component, and a printed table inside the page yields a strong competing rectangle.
 *     -> Straight-line candidates from an orientation-guided Hough transform (2 degree bins, adaptive
 *        voting spread, 3x3 smoothing with the theta wrap handled as (theta-180, -rho)), each selected
 *        line re-fitted by least squares.
 *  3. The best-scoring rectangle was taken independently in every frame; the page and the table inside it
 *     scored the same (area term saturated at 25 %), so the result alternated between them (jumping quad).
 *     -> "Outermost rule": a well-supported rectangle that CONTAINS the best one wins (a sheet contains its
 *        printed frames), plus temporal continuity with the previous live quad ([prior]).
 *  4. Side refinement fitted all aligned edge pixels in a +-3 px band. The sheet border and a printed frame
 *     just inside it have OPPOSITE polarity; mixing them tilted the fitted side.
 *     -> Pixels are split by polarity and the outer, well-supported group is fitted.
 *  5. Side support ignored polarity. A real sheet shows the SAME brightness step on its 4 sides.
 *     -> Polarity consistency is part of the score.
 * Python mirror (same thresholds) on synthetic scenes built from the reported form: max corner error
 * 0.1-0.5 % of the frame live (400 px) and 0.06-0.32 % on stills; previous version 8-10 % on white-desk
 * scenes (it locked onto the printed table).
 *
 * No fixed quad or bounding-box approximation is ever returned: when nothing qualifies, the result is null
 * and callers fall back to manual crop.
 */
object DocumentDetector {
    /** Minimum confidence for a detection to be reported at all. */
    const val MIN_CONFIDENCE = 0.60f
    /** Confidence required before live auto-capture may arm. */
    const val AUTO_CAPTURE_CONFIDENCE = 0.70f
    private const val LIVE_MAX_SIDE = 400
    private const val STILL_MAX_SIDE = 640
    private const val MAX_HULL_VERTICES = 32
    private const val HOUGH_BINS = 90 // 2 degrees per bin
    private const val MAX_REFINED_CANDIDATES = 24

    private val COS = FloatArray(HOUGH_BINS) { cos(Math.toRadians(2.0 * it)).toFloat() }
    private val SIN = FloatArray(HOUGH_BINS) { sin(Math.toRadians(2.0 * it)).toFloat() }

    // ------------------------------------------------------------------ public entry points

    /**
     * Detect on an upright bitmap (EXIF already applied). Thread-safe, CPU bound: call off the main thread.
     * Two scales: the sheet is found at <= 400 px (the scale the line voting is tuned for), then its sides
     * are re-fitted on a <= 640 px copy for precision.
     */
    fun detect(bitmap: Bitmap, expectedAspectRatio: Float? = null): DocumentDetection? {
        val gray = GrayImage.fromBitmap(bitmap, LIVE_MAX_SIDE) ?: return null
        val best = analyze(gray, expectedAspectRatio, null).firstOrNull() ?: return null
        if (best.confidence < MIN_CONFIDENCE) return null
        val fine = refineAtStillResolution(bitmap, best.quad, expectedAspectRatio)
        return if (fine != null) DocumentDetection(fine.first, fine.second, bitmap.width, bitmap.height)
        else DocumentDetection(best.quad, best.confidence, bitmap.width, bitmap.height)
    }

    /** Side re-fit of a detected quad on the <= 640 px image. Null when it does not improve / validate. */
    private fun refineAtStillResolution(bitmap: Bitmap, quad: DocumentQuad, expectedAspectRatio: Float?): Pair<DocumentQuad, Float>? {
        val fine = GrayImage.fromBitmap(bitmap, STILL_MAX_SIDE) ?: return null
        val edges = EdgeMap.build(fine)
        if (edges.lowCount < 50) return null
        val band = 2.5f * STILL_MAX_SIDE / LIVE_MAX_SIDE
        val refined = orderPx(refine(toPixels(quad, fine.width, fine.height), edges, band))
        val e = evaluate(refined, edges, expectedAspectRatio) ?: return null
        if (e.confidence < MIN_CONFIDENCE) return null
        return toNormalized(refined, fine.width, fine.height) to e.confidence
    }

    /**
     * Still-image detection guided by a prior quad (the live-preview quad at the moment of capture, in the
     * same normalized frame). Preference order:
     *  1. the detected sheet (the prior breaks ties between near-equal candidates),
     *  2. the prior refined to the still's edges when they still support it,
     *  3. the prior as-is (it was stable in preview; the user saw it).
     */
    fun detectWithPrior(bitmap: Bitmap, prior: DocumentQuad?, expectedAspectRatio: Float? = null): DocumentDetection? {
        if (prior == null) return detect(bitmap, expectedAspectRatio)
        val coarse = GrayImage.fromBitmap(bitmap, LIVE_MAX_SIDE)
            ?: return DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height)
        val best = analyze(coarse, expectedAspectRatio, prior).firstOrNull()
        if (best != null && best.confidence >= MIN_CONFIDENCE) {
            val fine = refineAtStillResolution(bitmap, best.quad, expectedAspectRatio)
            return if (fine != null) DocumentDetection(fine.first, fine.second, bitmap.width, bitmap.height)
            else DocumentDetection(best.quad, best.confidence, bitmap.width, bitmap.height)
        }
        val gray = GrayImage.fromBitmap(bitmap, STILL_MAX_SIDE)
            ?: return DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height)
        val edges = EdgeMap.build(gray)
        val priorPx = toPixels(prior, gray.width, gray.height)
        val refined = orderPx(refine(priorPx, edges))
        val refinedEval = evaluate(refined, edges, expectedAspectRatio)
        if (refinedEval != null && refinedEval.confidence >= 0.5f) {
            return DocumentDetection(toNormalized(refined, gray.width, gray.height), refinedEval.confidence, bitmap.width, bitmap.height)
        }
        return DocumentDetection(prior, 0.5f, bitmap.width, bitmap.height)
    }

    /**
     * CameraX fast path. Reads only the Y plane inside [ImageProxy.getCropRect] (with a ViewPort bound in a
     * UseCaseGroup this is exactly the region visible in the PreviewView), detects, and returns the quad in
     * the ORIENTED frame (rotationDegrees applied), sized cropW x cropH (swapped for 90/270).
     * [prior] is the previous tracked quad in the same ORIENTED frame (temporal continuity).
     */
    fun detectFromImageProxy(
        imageProxy: ImageProxy,
        expectedAspectRatio: Float? = null,
        prior: DocumentQuad? = null
    ): DocumentDetection? {
        val gray = GrayImage.fromImageProxy(imageProxy, LIVE_MAX_SIDE) ?: return null
        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        // Prior is oriented; the analysed buffer is not: rotate back by (360 - rotation).
        val sensorPrior = prior?.rotated((360 - rotation) % 360)?.sortCorners()
        val best = analyze(gray, expectedAspectRatio, sensorPrior).firstOrNull() ?: return null
        if (best.confidence < MIN_CONFIDENCE) return null
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

    private class Candidate(
        val quad: DocumentQuad,
        val px: FloatArray,
        val confidence: Float,
        val area: Float,
        val supportMin: Float
    )

    private class Eval(val confidence: Float, val area: Float, val supportMin: Float)

    /** Returns evaluated candidates; the selected sheet is FIRST, the rest follow by confidence. */
    private fun analyze(gray: GrayImage, expectedAspectRatio: Float?, prior: DocumentQuad?): List<Candidate> {
        val w = gray.width
        val h = gray.height
        if (w < 32 || h < 32) return emptyList()
        val edges = EdgeMap.build(gray)
        if (edges.lowCount < 50) return emptyList()

        // 1. Raw candidates: connected components (high-contrast sheets) + straight lines (weak borders).
        val raw = ArrayList<FloatArray>()
        val otsu = otsuThreshold(edges.blurred)
        val masks = listOf(
            edges.edge,
            dilate(edges.edge, w, h),
            BooleanArray(w * h) { edges.blurred[it] >= otsu },
            BooleanArray(w * h) { edges.blurred[it] < otsu }
        )
        val labeler = ComponentLabeler(w, h)
        for (mask in masks) {
            labeler.forEachComponent(mask) { minX, minY, maxX, maxY, count, hullPoints ->
                if (maxX - minX >= 0.2f * w && maxY - minY >= 0.2f * h && count >= 40) {
                    val hull = convexHull(hullPoints)
                    if (hull.size >= 4) {
                        maxAreaQuad(simplifyHull(hull, MAX_HULL_VERTICES))?.let { raw.add(orderPx(it)) }
                    }
                }
            }
        }
        raw.addAll(houghQuads(edges))

        // 2. Cheap pre-screen + de-duplication, so only the best few are refined and fully evaluated.
        val screened = raw.mapNotNull { q -> quickScore(q, edges)?.let { it to q } }.sortedByDescending { it.first }
        val kept = ArrayList<FloatArray>()
        for ((_, q) in screened) {
            if (kept.size >= MAX_REFINED_CANDIDATES) break
            if (kept.any { maxCornerDistanceNorm(it, q, w, h) < 0.02f }) continue
            kept.add(q)
        }

        // 3. Sub-pixel refinement + full evaluation.
        val results = ArrayList<Candidate>()
        for (q in kept) {
            val refined = orderPx(refine(q, edges))
            var bestPx: FloatArray? = null
            var bestEval: Eval? = null
            for (option in arrayOf(refined, q)) {
                val e = evaluate(option, edges, expectedAspectRatio) ?: continue
                if (bestEval == null || e.confidence > bestEval.confidence) {
                    bestEval = e
                    bestPx = option
                }
            }
            if (bestPx != null && bestEval != null) {
                results.add(Candidate(toNormalized(bestPx, w, h), bestPx, bestEval.confidence, bestEval.area, bestEval.supportMin))
            }
        }
        if (results.isEmpty()) return emptyList()
        results.sortByDescending { it.confidence }

        // 4. Selection: outermost well-supported sheet, then temporal continuity.
        var best = results[0]
        var improved = true
        while (improved) {
            improved = false
            for (c in results) {
                if (c === best) continue
                if (c.confidence >= max(MIN_CONFIDENCE, best.confidence - 0.10f) &&
                    c.supportMin >= 0.70f &&
                    c.area >= 1.10f * best.area &&
                    contains(c.quad, best.quad)
                ) {
                    best = c
                    improved = true
                }
            }
        }
        if (prior != null) {
            val near = results.filter { it.quad.maxCornerDistance(prior) < 0.06f }.maxByOrNull { it.confidence }
            if (near != null && near !== best && near.confidence >= best.confidence - 0.12f &&
                !(best.area >= 1.10f * near.area && contains(best.quad, near.quad))
            ) {
                best = near
            }
        }
        val out = ArrayList<Candidate>(results.size)
        out.add(best)
        for (c in results) if (c !== best) out.add(c)
        return out
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

    /**
     * [edge]: Canny-style hysteresis map (thresholds from the content's own edge-strength distribution),
     * used for connected-component candidates.
     * [low]: every non-maximum-suppressed edge above a NOISE-relative threshold. It keeps weak but real
     * sheet borders (white paper on a light desk) that the content-relative threshold drops; used for
     * straight-line candidates, refinement and side support (all of which also check orientation).
     */
    private class EdgeMap(
        val width: Int,
        val height: Int,
        val blurred: FloatArray,
        val gx: FloatArray,
        val gy: FloatArray,
        val edge: BooleanArray,
        val low: BooleanArray,
        val lowIndices: IntArray,
        val lowCount: Int
    ) {
        companion object {
            fun build(gray: GrayImage): EdgeMap {
                val w = gray.width
                val h = gray.height
                val blurred = DocumentDetector.gaussianBlur5(gray.data, w, h)
                val gx = FloatArray(w * h)
                val gy = FloatArray(w * h)
                val mag = FloatArray(w * h)
                val magHist = IntArray(2048)
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
                        val m = sqrt(sx * sx + sy * sy)
                        mag[r + x] = m
                        magHist[m.toInt().coerceIn(0, 2047)]++
                    }
                }
                // Median gradient magnitude = noise / texture floor of this frame.
                val interior = (w - 2) * (h - 2)
                var medMag = 0f
                run {
                    var acc = 0
                    for (b in magHist.indices) {
                        acc += magHist[b]
                        if (acc * 2 >= interior) { medMag = b.toFloat(); break }
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
                val low = BooleanArray(w * h)
                if (maxMag <= 0f) return EdgeMap(w, h, blurred, gx, gy, edge, low, IntArray(0), 0)
                // Content-relative hysteresis thresholds (85th percentile of thin-edge strengths).
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
                val p85 = hiBin.toFloat() / (bins - 1) * maxMag
                val hi = max(p85, 20f)
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
                // Noise-relative threshold for straight-border evidence.
                val lowT = max(12f, max(0.25f * p85, 2.5f * medMag))
                var count = 0
                for (i in thin.indices) if (thin[i] >= lowT) { low[i] = true; count++ }
                val idx = IntArray(count)
                var k = 0
                for (i in thin.indices) if (low[i]) idx[k++] = i
                return EdgeMap(w, h, blurred, gx, gy, edge, low, idx, count)
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

    // ------------------------------------------------------------------ straight-line candidates

    /** A straight line x*cos(theta) + y*sin(theta) = rho (theta in degrees, continuous after refit). */
    private class Line(val votes: Float, val thetaDeg: Float, val rho: Float, val horizontal: Boolean)

    /**
     * Orientation-guided Hough transform on the LOW edge map. Each edge pixel votes only for lines whose
     * normal is close to its own gradient (+-6 degrees for strong edges, +-12 for weak ones).
     * Peaks = all 5x5 local maxima (theta wrap handled as (theta-180, -rho)); candidate lines = strongest
     * ones + the outermost ones (the sheet border is the outermost line and may be weaker than printed
     * table lines or rows of text). Every selected line is re-fitted by least squares on its own pixels
     * (one polarity only), removing the 2-degree bin quantisation. Every H-pair x V-pair forms a quad.
     */
    private fun houghQuads(e: EdgeMap): List<FloatArray> {
        val w = e.width
        val h = e.height
        if (e.lowCount < 20) return emptyList()
        val nb = HOUGH_BINS
        val diag = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val nr = 2 * diag + 1
        val acc = FloatArray(nb * nr)
        // Voting spread adapts to edge strength: the orientation of a weak border (low contrast + sensor
        // noise) is only known to about +-12 degrees (measured +-15 on a white-on-white page), a strong
        // edge to +-6.
        val mags = FloatArray(e.lowCount) { k -> val i = e.lowIndices[k]; hypot(e.gx[i], e.gy[i]) }
        val weakLimit = 3f * (mags.copyOf().also { it.sort() }.let { if (it.isEmpty()) 0f else it[it.size / 2] })
        for ((k, i) in e.lowIndices.withIndex()) {
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            var deg = Math.toDegrees(atan2(e.gy[i].toDouble(), e.gx[i].toDouble())).toFloat()
            deg = ((deg % 180f) + 180f) % 180f
            val base = (deg / 2f).roundToInt()
            val spread = if (mags[k] < weakLimit) 6 else 3
            for (dt in -spread..spread) {
                val t = (((base + dt) % nb) + nb) % nb
                val r = (x * COS[t] + y * SIN[t]).roundToInt() + diag
                if (r in 0 until nr) acc[t * nr + r] += 1f
            }
        }
        // 3x3 box smoothing. Across the 0/180 wrap the same line has rho -> -rho (index nr-1-r).
        val sm = FloatArray(nb * nr)
        for (t in 0 until nb) {
            val tp = (t - 1 + nb) % nb
            val tn = (t + 1) % nb
            val mirrorPrev = t == 0
            val mirrorNext = t == nb - 1
            val op = tp * nr; val oc = t * nr; val on = tn * nr
            for (r in 1 until nr - 1) {
                var s = acc[oc + r - 1] + acc[oc + r] + acc[oc + r + 1]
                for (dr in -1..1) {
                    val rr = r + dr
                    s += if (mirrorPrev) acc[op + (nr - 1 - rr)] else acc[op + rr]
                    s += if (mirrorNext) acc[on + (nr - 1 - rr)] else acc[on + rr]
                }
                sm[oc + r] = s / 3f
            }
        }
        // All 5x5 local maxima above the vote floor, strongest first.
        val minVotes = 0.15f * min(w, h)
        val maxima = ArrayList<Int>() // accumulator cell indices
        for (t in 0 until nb) {
            for (r in 2 until nr - 2) {
                val v = sm[t * nr + r]
                if (v < minVotes) continue
                var isMax = true
                loop@ for (dt in -2..2) {
                    val tt = (t + dt).coerceIn(0, nb - 1)
                    for (dr in -2..2) {
                        if (sm[tt * nr + r + dr] > v) { isMax = false; break@loop }
                    }
                }
                if (isMax) maxima.add(t * nr + r)
            }
        }
        val sortedMaxima = maxima.sortedByDescending { sm[it] }
        val peaks = ArrayList<Line>()
        val peakBins = ArrayList<IntArray>() // [thetaBin, rho]
        for (cell in sortedMaxima) {
            if (peaks.size >= 120) break
            val t = cell / nr
            val rho = cell % nr - diag
            var dup = false
            for (pb in peakBins) {
                val dt = t - pb[0]
                if (abs(dt) <= 2 && abs(rho - pb[1]) <= 2) { dup = true; break }
                if (abs(dt) >= nb - 2 && abs(rho + pb[1]) <= 2) { dup = true; break } // wrapped
            }
            if (dup) continue
            peakBins.add(intArrayOf(t, rho))
            val deg = 2 * t
            peaks.add(Line(sm[cell], deg.toFloat(), rho.toFloat(), deg in 45 until 135))
        }
        fun position(l: Line): Float {
            val c = cos(Math.toRadians(l.thetaDeg.toDouble())).toFloat()
            val s = sin(Math.toRadians(l.thetaDeg.toDouble())).toFloat()
            return if (l.horizontal) (l.rho - c * w / 2f) / s else (l.rho - s * h / 2f) / c
        }
        fun pick(family: List<Line>): List<Line> {
            val byPos = family.sortedBy { position(it) }
            val out = ArrayList<Line>()
            for (l in family.take(5) + byPos.take(3) + byPos.takeLast(3)) if (out.none { it === l }) out.add(l)
            return out
        }
        val hs = pick(peaks.filter { it.horizontal }).map { fitLine(it, e) }
        val vs = pick(peaks.filterNot { it.horizontal }).map { fitLine(it, e) }
        fun intersect(a: Line, b: Line): FloatArray? {
            val c1 = cos(Math.toRadians(a.thetaDeg.toDouble())); val s1 = sin(Math.toRadians(a.thetaDeg.toDouble()))
            val c2 = cos(Math.toRadians(b.thetaDeg.toDouble())); val s2 = sin(Math.toRadians(b.thetaDeg.toDouble()))
            val det = c1 * s2 - s1 * c2
            if (abs(det) < 1e-3) return null
            val x = (a.rho * s2 - s1 * b.rho) / det
            val y = (c1 * b.rho - a.rho * c2) / det
            if (!x.isFinite() || !y.isFinite()) return null
            return floatArrayOf(x.toFloat(), y.toFloat())
        }
        val quads = ArrayList<FloatArray>()
        for (i in hs.indices) for (j in i + 1 until hs.size) for (k in vs.indices) for (l in k + 1 until vs.size) {
            val p0 = intersect(hs[i], vs[k]) ?: continue
            val p1 = intersect(hs[i], vs[l]) ?: continue
            val p2 = intersect(hs[j], vs[l]) ?: continue
            val p3 = intersect(hs[j], vs[k]) ?: continue
            quads.add(orderPx(floatArrayOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1])))
        }
        return quads
    }

    /**
     * Least-squares refit of a Hough line on the LOW-map pixels within 2 px then 1.2 px of it whose gradient
     * is within ~21 degrees of its normal, using ONE polarity (the group lying on the line) so that two
     * parallel edges (sheet border + printed frame) are never averaged. Returns the input line when support
     * is too weak.
     */
    private fun fitLine(line: Line, e: EdgeMap): Line {
        val w = e.width
        var theta = Math.toRadians(line.thetaDeg.toDouble())
        var rho = line.rho.toDouble()
        for (band in doubleArrayOf(2.0, 1.2)) {
            val c = cos(theta); val s = sin(theta)
            // [n, sx, sy, sxx, syy, sxy, sum of signed distance] for positive and negative polarity
            val pos = DoubleArray(7); val neg = DoubleArray(7)
            for (i in e.lowIndices) {
                val x = (i % w).toDouble(); val y = (i / w).toDouble()
                val dist = x * c + y * s - rho
                if (abs(dist) > band) continue
                val gxv = e.gx[i].toDouble(); val gyv = e.gy[i].toDouble()
                val gm = hypot(gxv, gyv) + 1e-6
                val sd = (gxv * c + gyv * s) / gm
                if (abs(sd) < 0.93) continue
                val a = if (sd > 0) pos else neg
                a[0] += 1.0; a[1] += x; a[2] += y; a[3] += x * x; a[4] += y * y; a[5] += x * y; a[6] += dist
            }
            // One polarity (never average two parallel edges): the group lying ON the peak line.
            val a = listOf(pos, neg).filter { it[0] >= 10.0 }.minByOrNull { abs(it[6] / it[0]) } ?: return line
            val mx = a[1] / a[0]; val my = a[2] / a[0]
            val cxx = a[3] / a[0] - mx * mx; val cyy = a[4] / a[0] - my * my; val cxy = a[5] / a[0] - mx * my
            val dir = 0.5 * atan2(2 * cxy, cxx - cyy)
            theta = dir + Math.PI / 2
            rho = mx * cos(theta) + my * sin(theta)
        }
        return Line(line.votes, Math.toDegrees(theta).toFloat(), rho.toFloat(), line.horizontal)
    }

    // ------------------------------------------------------------------ components & hull

    /**
     * 8-connected component labeling that reports, per component, the bounding box, pixel count and the
     * left/right extreme pixel of every row (the convex hull of a component equals the hull of these row
     * extremes, which keeps hull input O(height)).
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

    /** Andrew's monotone chain convex hull. */
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

    private fun maxCornerDistanceNorm(a: FloatArray, b: FloatArray, w: Int, h: Int): Float {
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot((a[2 * i] - b[2 * i]) / w, (a[2 * i + 1] - b[2 * i + 1]) / h))
        return m
    }

    /** True when every corner of [inner] lies inside the convex quad [outer] (normalized, small tolerance). */
    private fun contains(outer: DocumentQuad, inner: DocumentQuad, tol: Float = 0.01f): Boolean {
        val o = outer.points()
        for (p in inner.points()) {
            var sign = 0
            for (i in 0 until 4) {
                val a = o[i]; val b = o[(i + 1) % 4]
                val c = cross(a.x, a.y, b.x, b.y, p.x, p.y)
                val s = if (c > tol) 1 else if (c < -tol) -1 else 0
                if (s == 0) continue
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    private fun areaPx(q: FloatArray): Float {
        var area2 = 0f
        for (i in 0 until 4) {
            val a = 2 * i; val b = 2 * ((i + 1) % 4)
            area2 += q[a] * q[b + 1] - q[b] * q[a + 1]
        }
        return abs(area2) / 2f
    }

    private fun insideFrame(q: FloatArray, w: Int, h: Int): Boolean {
        for (i in 0 until 4) {
            val x = q[2 * i]; val y = q[2 * i + 1]
            if (!x.isFinite() || !y.isFinite()) return false
            if (x < -0.03f * w || x > 1.03f * w || y < -0.03f * h || y > 1.03f * h) return false
        }
        return true
    }

    /** Coarse ranking before refinement: geometry, edge support on all 4 sides and size (outer first). */
    private fun quickScore(q: FloatArray, e: EdgeMap): Float? {
        if (!insideFrame(q, e.width, e.height)) return null
        val area = areaPx(q) / (e.width.toFloat() * e.height)
        if (area < 0.05f || area > 0.995f) return null
        var sum = 0f
        var mn = 1f
        for (s in 0 until 4) {
            val v = sideSupport(q, s, e).fraction
            if (v < 0.3f) return null // early exit: most line combinations fail on the first sides
            sum += v
            mn = min(mn, v)
        }
        return sum / 4f + 0.5f * mn + 0.6f * min(1f, area / 0.5f)
    }

    /**
     * Re-fits every side to the aligned LOW-map edge pixels in a +-[band] px band (inner 80 % of the side),
     * then intersects adjacent lines. Pixels are split by polarity; the OUTER group with enough support is
     * fitted (a sheet border and a printed frame just inside it have opposite polarity). A side with too
     * little evidence keeps its original corners.
     */
    private fun refine(q: FloatArray, edges: EdgeMap, band: Float = 2.5f): FloatArray {
        val w = edges.width
        val maxShift = 0.08f * max(edges.width, edges.height)
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val lines = arrayOfNulls<FloatArray>(4) // [cx, cy, dx, dy]
        for (s in 0 until 4) {
            val ax = q[2 * s]; val ay = q[2 * s + 1]
            val bx = q[2 * ((s + 1) % 4)]; val by = q[2 * ((s + 1) % 4) + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 8f) return q
            val ux = (bx - ax) / len; val uy = (by - ay) / len
            var nx = -uy; var ny = ux
            if ((cx - (ax + bx) / 2f) * nx + (cy - (ay + by) / 2f) * ny < 0f) { nx = -nx; ny = -ny } // inward
            // Accumulators per polarity: [n, sx, sy, sxx, syy, sxy]
            val accs = arrayOf(DoubleArray(6), DoubleArray(6))
            for (i in edges.lowIndices) {
                val x = (i % w).toFloat(); val y = (i / w).toFloat()
                val rx = x - ax; val ry = y - ay
                val t = rx * ux + ry * uy
                if (t < 0.1f * len || t > 0.9f * len) continue
                if (abs(rx * nx + ry * ny) > band) continue
                val gxv = edges.gx[i]; val gyv = edges.gy[i]
                val gm = hypot(gxv, gyv) + 1e-6f
                val d = (gxv * nx + gyv * ny) / gm
                if (abs(d) < 0.8f) continue
                val a = accs[if (d > 0f) 0 else 1]
                a[0] += 1.0; a[1] += x.toDouble(); a[2] += y.toDouble()
                a[3] += x.toDouble() * x; a[4] += y.toDouble() * y; a[5] += x.toDouble() * y
            }
            var chosen: DoubleArray? = null
            var bestOuter = -Double.MAX_VALUE
            for (a in accs) {
                if (a[0] < max(8.0, 0.35 * len)) continue
                val mx = a[1] / a[0]; val my = a[2] / a[0]
                val outer = -((mx - ax) * nx + (my - ay) * ny) // larger = further outside
                if (chosen == null || outer > bestOuter + 0.5) { chosen = a; bestOuter = outer }
            }
            if (chosen == null) {
                val merged = DoubleArray(6) { accs[0][it] + accs[1][it] }
                if (merged[0] < max(8.0, 0.2 * len)) continue
                chosen = merged
            }
            val n = chosen[0]
            val mx = chosen[1] / n; val my = chosen[2] / n
            val cxx = chosen[3] / n - mx * mx; val cyy = chosen[4] / n - my * my; val cxy = chosen[5] / n - mx * my
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

    /** Returns confidence (+ area, weakest side) or null when the quad is not a plausible document. */
    private fun evaluate(q: FloatArray, edges: EdgeMap, expectedAspectRatio: Float?): Eval? {
        val w = edges.width; val h = edges.height
        var sign = 0
        for (i in 0 until 4) {
            val a = 2 * i; val b = 2 * ((i + 1) % 4); val c = 2 * ((i + 2) % 4)
            val cr = cross(q[a], q[a + 1], q[b], q[b + 1], q[c], q[c + 1])
            if (abs(cr) < 1e-6f) return null
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return null
        }
        if (!insideFrame(q, w, h)) return null
        val relArea = areaPx(q) / (w.toFloat() * h)
        if (relArea < 0.05f || relArea > 0.995f) return null
        val sides = FloatArray(4) { i -> hypot(q[2 * ((i + 1) % 4)] - q[2 * i], q[2 * ((i + 1) % 4) + 1] - q[2 * i + 1]) }
        if (minOf(minOf(sides[0], sides[1]), minOf(sides[2], sides[3])) < 0.08f * min(w, h)) return null
        if (min(sides[0], sides[2]) / max(sides[0], sides[2]) < 0.4f) return null
        if (min(sides[1], sides[3]) / max(sides[1], sides[3]) < 0.4f) return null
        var supportSum = 0f
        var supportMin = 1f
        var polaritySum = 0
        var hitTotal = 0
        for (s in 0 until 4) {
            val r = sideSupport(q, s, edges)
            supportSum += r.fraction
            supportMin = min(supportMin, r.fraction)
            polaritySum += r.polarity
            hitTotal += r.hits
        }
        val supportMean = supportSum / 4f
        if (supportMin < 0.35f || supportMean < 0.55f) return null
        val angle = rightAngleScore(q)
        if (angle < 0.75f) return null
        // A real sheet shows the SAME brightness step (paper vs background) on all four sides.
        val polarity = if (hitTotal == 0) 0f else abs(polaritySum).toFloat() / hitTotal
        var conf = 0.40f * supportMean + 0.15f * supportMin + 0.15f * angle + 0.15f * min(1f, relArea / 0.25f) + 0.15f * polarity
        if (expectedAspectRatio != null && expectedAspectRatio > 0f) {
            val sw = (sides[0] + sides[2]) / 2f
            val sh = (sides[1] + sides[3]) / 2f
            val ratio = max(sw, sh) / max(1e-3f, min(sw, sh))
            val dev = abs(ratio - expectedAspectRatio) / expectedAspectRatio
            conf *= max(0.6f, 1f - dev * 0.8f)
        }
        return Eval(conf.coerceIn(0f, 1f), relArea, supportMin)
    }

    private class SideSupport(val fraction: Float, val hits: Int, val polarity: Int)

    /**
     * Fraction of samples along side [s] that have an aligned LOW-map edge within +-2 px, and the sum of
     * their polarities (+1 = brighter inside, -1 = darker inside).
     */
    private fun sideSupport(q: FloatArray, s: Int, edges: EdgeMap): SideSupport {
        val w = edges.width; val h = edges.height
        val ax = q[2 * s]; val ay = q[2 * s + 1]
        val bx = q[2 * ((s + 1) % 4)]; val by = q[2 * ((s + 1) % 4) + 1]
        val dx = bx - ax; val dy = by - ay
        val len = max(hypot(dx, dy), 1e-3f)
        var nx = -dy / len; var ny = dx / len
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        if ((cx - (ax + bx) / 2f) * nx + (cy - (ay + by) / 2f) * ny < 0f) { nx = -nx; ny = -ny } // inward
        val samples = max(12, (len / 3f).toInt())
        var hit = 0
        var pol = 0
        for (k in 0 until samples) {
            val t = (k + 0.5f) / samples
            val px = ax + dx * t; val py = ay + dy * t
            for (o in OFFSETS) {
                val x = (px + nx * o).roundToInt(); val y = (py + ny * o).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val i = y * w + x
                if (!edges.low[i]) continue
                val gxv = edges.gx[i]; val gyv = edges.gy[i]
                val gm = hypot(gxv, gyv) + 1e-6f
                val d = (gxv * nx + gyv * ny) / gm
                if (abs(d) > 0.7f) {
                    hit++
                    pol += if (d > 0f) 1 else -1
                    break
                }
            }
        }
        return SideSupport(hit.toFloat() / samples, hit, pol)
    }

    private val OFFSETS = intArrayOf(0, -1, 1, -2, 2)

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
