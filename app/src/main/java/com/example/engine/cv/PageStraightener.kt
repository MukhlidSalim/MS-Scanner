package com.example.engine.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Automatic page straightening applied after the perspective warp (DocumentPipeline.renderBitmap):
 *
 *  1. [trimBackgroundSlivers]: removes thin strips of desk / background left along the page borders when the
 *     detected border lies a few pixels outside the sheet (max 2.5 % per side, only clearly darker lines).
 *  2. [deskew]: measures the residual text skew with a projection profile (rows of the binarized text give the
 *     sharpest profile when the lines are horizontal) and rotates the page by that angle. Works for pages
 *     whose four edges were not visible (no perspective crop) and for small errors after the crop.
 *
 * Conservative by design: no correction below 0.3°, above ±9.5°, on pages without text (photos, blank pages)
 * or when the measured profile is not clearly better than the uncorrected one. Validated on synthetic text
 * pages rotated from -9° to +9° (estimation error < 0.1°) and on empty / textured backgrounds (no correction).
 * Everything runs off the main thread (called from DocumentPipeline on Dispatchers.Default).
 */
object PageStraightener {
    private const val ANALYSIS_MAX_SIDE = 1000
    private const val MAX_ANGLE = 10.0
    private const val MIN_ANGLE = 0.3
    private const val MIN_CONFIDENCE = 0.25
    private const val MAX_TRIM_FRACTION = 0.025

    // Small cache so repeated previews of the same raw + crop (editor sliders) do not re-measure the skew.
    private val angleCache = object : LinkedHashMap<String, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > 12
    }

    /**
     * Trim (when [trim]) + deskew. Returns [src] itself when nothing changes. [cacheKey] identifies the raw
     * image + crop that produced [src] (repeated previews reuse the measured angle).
     */
    fun straighten(src: Bitmap, cacheKey: String?, trim: Boolean): Bitmap {
        if (!OpenCvBootstrap.ensureInitialized()) return src
        return try {
            val trimmed = if (trim) trimBackgroundSlivers(src) else src
            val angle = cacheKey?.let { synchronized(angleCache) { angleCache[it] } } ?: estimateSkew(trimmed).also { a ->
                if (cacheKey != null) synchronized(angleCache) { angleCache[cacheKey] = a }
            }
            if (angle == 0.0) trimmed else {
                val rotated = rotate(trimmed, angle)
                if (trimmed !== src && rotated !== trimmed) trimmed.recycle()
                rotated
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            src
        }
    }

    /** Cheap content fingerprint (8x8 pixel samples + size) used to build cache keys. */
    fun fingerprint(bmp: Bitmap): String {
        var h = 17L * bmp.width + bmp.height
        for (j in 0 until 8) for (i in 0 until 8) {
            h = h * 31 + bmp.getPixel(((i + 0.5) * bmp.width / 8).toInt(), ((j + 0.5) * bmp.height / 8).toInt())
        }
        return h.toString(16)
    }

    /** Returns the correction angle in degrees (0.0 = leave as is). Same convention as getRotationMatrix2D. */
    fun estimateSkew(src: Bitmap): Double {
        if (src.width < 64 || src.height < 64) return 0.0
        val gray = toGray(src, ANALYSIS_MAX_SIDE)
        val bw = Mat()
        try {
            Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
            val w = bw.cols(); val h = bw.rows()
            val m = (0.04 * min(w, h)).toInt()
            // Ignore the page borders (shadows, background left by the crop).
            bw.rowRange(0, m).setTo(Scalar(0.0)); bw.rowRange(h - m, h).setTo(Scalar(0.0))
            bw.colRange(0, m).setTo(Scalar(0.0)); bw.colRange(w - m, w).setTo(Scalar(0.0))
            val ink = Core.countNonZero(bw).toDouble() / (w.toDouble() * h)
            if (ink < 0.004 || ink > 0.35) return 0.0 // no text, or a photo / dark page
            val rotated = Mat(); val rowSum = Mat()
            fun score(a: Double): Double {
                val rm = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), a, 1.0)
                Imgproc.warpAffine(bw, rotated, rm, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_NEAREST)
                rm.release()
                Core.reduce(rotated, rowSum, 1, Core.REDUCE_SUM, CvType.CV_32S)
                val p = IntArray(h); rowSum.get(0, 0, p)
                var s = 0.0
                for (i in 1 until h) { val d = (p[i] - p[i - 1]).toDouble(); s += d * d }
                return s
            }
            try {
                var bestA = 0.0; var bestS = -1.0
                var a = -MAX_ANGLE
                while (a <= MAX_ANGLE + 1e-9) {
                    val s = score(a); if (s > bestS) { bestS = s; bestA = a }; a += 0.5
                }
                var fineA = bestA; var fineS = bestS
                var f = bestA - 0.5
                while (f <= bestA + 0.5 + 1e-9) {
                    val s = score(f); if (s > fineS) { fineS = s; fineA = f }; f += 0.1
                }
                val base = score(0.0)
                val confidence = (fineS - base) / max(fineS, 1e-9)
                val angle = (fineA * 10).roundToInt() / 10.0
                return if (abs(angle) >= MIN_ANGLE && abs(angle) <= MAX_ANGLE - 0.5 && confidence >= MIN_CONFIDENCE) angle else 0.0
            } finally {
                rotated.release(); rowSum.release()
            }
        } finally {
            gray.release(); bw.release()
        }
    }

    /**
     * Rotates around the centre with a white background. Up to 3° the page keeps its size (only margin pixels
     * leave the frame); beyond that the canvas grows so no content is cut.
     */
    private fun rotate(src: Bitmap, angle: Double): Bitmap {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val out = Mat()
        try {
            val w = rgba.cols(); val h = rgba.rows()
            val rad = Math.toRadians(abs(angle))
            val (nw, nh) = if (abs(angle) <= 3.0) w to h else
                (w * cos(rad) + h * sin(rad)).roundToInt() to (w * sin(rad) + h * cos(rad)).roundToInt()
            val rm = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angle, 1.0)
            // shift so the rotated page is centred on the (possibly larger) canvas
            rm.put(0, 2, rm.get(0, 2)[0] + (nw - w) / 2.0)
            rm.put(1, 2, rm.get(1, 2)[0] + (nh - h) / 2.0)
            Imgproc.warpAffine(
                rgba, out, rm, Size(nw.toDouble(), nh.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0)
            )
            rm.release()
            val bmp = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            rgba.release(); out.release()
        }
    }

    /** Removes background strips (lines much darker than the paper) along the four borders. */
    fun trimBackgroundSlivers(src: Bitmap): Bitmap {
        if (src.width < 64 || src.height < 64) return src
        val gray = toGray(src, 800)
        try {
            val w = gray.cols(); val h = gray.rows()
            val data = ByteArray(w * h); gray.get(0, 0, data)
            fun v(x: Int, y: Int) = data[y * w + x].toInt() and 0xFF
            // Paper level = 90th percentile of the centre (text excluded).
            val centre = ArrayList<Int>()
            for (y in (h * 0.2).toInt() until (h * 0.8).toInt() step 2) for (x in (w * 0.2).toInt() until (w * 0.8).toInt() step 2) centre.add(v(x, y))
            if (centre.isEmpty()) return src
            centre.sort()
            val paper = centre[(centre.size * 0.9).toInt().coerceAtMost(centre.size - 1)]
            val limit = paper * 0.72
            fun median(values: IntArray): Int { values.sort(); return values[values.size / 2] }
            fun count(n: Int, line: (Int) -> IntArray): Int {
                var k = 0
                while (k < n && median(line(k)) < limit) k++
                return k
            }
            val mh = (h * MAX_TRIM_FRACTION).toInt(); val mw = (w * MAX_TRIM_FRACTION).toInt()
            val top = count(mh) { i -> IntArray(w) { x -> v(x, i) } }
            val bottom = count(mh) { i -> IntArray(w) { x -> v(x, h - 1 - i) } }
            val left = count(mw) { i -> IntArray(h) { y -> v(i, y) } }
            val right = count(mw) { i -> IntArray(h) { y -> v(w - 1 - i, y) } }
            if (top + bottom + left + right == 0) return src
            val f = src.width.toDouble() / w
            val x0 = (left * f).roundToInt(); val y0 = (top * src.height.toDouble() / h).roundToInt()
            val x1 = src.width - (right * f).roundToInt(); val y1 = src.height - (bottom * src.height.toDouble() / h).roundToInt()
            if (x1 - x0 < src.width / 2 || y1 - y0 < src.height / 2) return src
            return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        } finally {
            gray.release()
        }
    }

    private fun toGray(src: Bitmap, maxSide: Int): Mat {
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); rgba.release()
        val s = min(1.0, maxSide.toDouble() / max(gray.cols(), gray.rows()))
        if (s >= 1.0) return gray
        val small = Mat(); Imgproc.resize(gray, small, Size(gray.cols() * s, gray.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        return small
    }
}
