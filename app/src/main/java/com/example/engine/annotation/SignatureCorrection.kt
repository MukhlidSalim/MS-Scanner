package com.example.engine.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced signature auto-correction ("Signature Cleanup"): turns a raw finger-drawn stroke (a jittery
 * polyline sampled from touch events) into a stroke that reads as a natural, professional ink signature.
 *
 * This replaces the previous approach (straight-line / plain quadTo segments drawn 1:1 from the raw
 * touch points) with a proper three-stage pipeline, run per stroke:
 *
 *  1. DENOISE — collapses near-duplicate / micro-jitter points (hand tremor, touch-sensor noise) with a
 *     distance threshold, THEN applies a small moving-average pass. This removes the "shaky hand" look
 *     without flattening intentional shape, because step 2 re-samples geometry, not raw pixels.
 *
 *  2. SMOOTH FIT — re-parameterises the denoised points by arc length and fits a Catmull-Rom spline
 *     through them, evaluating it at a fixed fine step. A Catmull-Rom spline passes exactly through every
 *     input point (unlike Bezier, which only approximates), which is what makes the result look like a
 *     continuous, deliberate pen stroke rather than a sequence of short segments glued together.
 *
 *  3. PRESSURE SIMULATION — computes local drawing speed between consecutive resampled points (using the
 *     real touch timestamps recorded while drawing) and maps speed to stroke width: slower movement (the
 *     user carefully forming a letter) renders a thicker line, fast movement (a quick flourish/connector)
 *     renders a thinner line — this is exactly how a real ballpoint/ink pen behaves under finger pressure.
 *     Each stroke additionally TAPERS to a point at its start and end (where a real pen touches down and
 *     lifts off), instead of ending abruptly with a flat round cap.
 *
 * The output is rendered at [outputScale]x the input canvas size (capped) so the final bitmap used for
 * placement/printing never looks pixelated when enlarged, and the background stays fully transparent —
 * no box, no card, no screenshot artefacts.
 */
object SignatureCorrection {

    /** One recorded touch sample: position + time (elapsedRealtime-style millis, monotonic per stroke). */
    data class TimedPoint(val x: Float, val y: Float, val tMs: Long)

    data class CorrectedStroke(val points: List<TimedPoint>, val colorArgb: Int)

    private const val DENOISE_MIN_DISTANCE_PX = 1.1f      // collapse points closer than this
    private const val SMOOTHING_WINDOW = 3                 // moving-average window (points), odd
    private const val RESAMPLE_STEP_PX = 2.0f              // arc-length spacing fed into the spline
    private const val MIN_STROKE_WIDTH = 2.6f
    private const val MAX_STROKE_WIDTH = 7.2f
    private const val TAPER_FRACTION = 0.08f               // portion of stroke length that tapers at each end
    private const val SPEED_LOW_PX_MS = 0.15f              // slow -> thick
    private const val SPEED_HIGH_PX_MS = 1.6f              // fast -> thin
    private const val MAX_OUTPUT_SIDE = 2200

    /**
     * Builds the final, corrected signature bitmap from the raw recorded strokes.
     * [canvasSize] is the on-screen drawing surface size (width, height) the points were captured in.
     */
    fun render(strokes: List<CorrectedStroke>, canvasWidth: Int, canvasHeight: Int): Bitmap? {
        if (strokes.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return null
        val scale = min(2f, MAX_OUTPUT_SIDE.toFloat() / max(canvasWidth, canvasHeight))
        val outW = (canvasWidth * scale).toInt().coerceAtLeast(1)
        val outH = (canvasHeight * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888) // transparent by default
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)

        var anyDrawn = false
        for (stroke in strokes) {
            val raw = stroke.points
            if (raw.size < 2) continue

            val denoised = denoise(raw)
            if (denoised.size < 2) continue

            val speeds = localSpeeds(denoised)
            val resampled = resampleWithCatmullRom(denoised, RESAMPLE_STEP_PX)
            if (resampled.size < 2) continue

            val widths = widthsForResampled(resampled, denoised, speeds)
            drawTaperedStroke(canvas, resampled, widths, stroke.colorArgb)
            anyDrawn = true
        }
        return if (anyDrawn) bmp else null
    }

    // ------------------------------------------------------------------------------------- stage 1

    /** Collapses micro-jitter points, then applies a short moving average to the remaining polyline. */
    private fun denoise(points: List<TimedPoint>): List<TimedPoint> {
        if (points.size < 2) return points
        val collapsed = ArrayList<TimedPoint>(points.size)
        collapsed.add(points.first())
        for (p in points.drop(1)) {
            val last = collapsed.last()
            if (hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) >= DENOISE_MIN_DISTANCE_PX) {
                collapsed.add(p)
            }
        }
        if (collapsed.size < 3) return collapsed
        val half = SMOOTHING_WINDOW / 2
        val smoothed = ArrayList<TimedPoint>(collapsed.size)
        for (i in collapsed.indices) {
            if (i == 0 || i == collapsed.lastIndex) {
                smoothed.add(collapsed[i]) // keep exact start/end (where the pen touches down / lifts)
                continue
            }
            var sx = 0f; var sy = 0f; var n = 0
            for (k in -half..half) {
                val idx = (i + k).coerceIn(0, collapsed.lastIndex)
                sx += collapsed[idx].x
                sy += collapsed[idx].y
                n++
            }
            smoothed.add(TimedPoint(sx / n, sy / n, collapsed[i].tMs))
        }
        return smoothed
    }

    // ------------------------------------------------------------------------------------- stage 2

    /** Catmull-Rom spline through [pts], evaluated at roughly [stepPx] arc-length spacing. */
    private fun resampleWithCatmullRom(pts: List<TimedPoint>, stepPx: Float): List<PointF> {
        if (pts.size < 2) return pts.map { PointF(it.x, it.y) }
        if (pts.size == 2) return listOf(PointF(pts[0].x, pts[0].y), PointF(pts[1].x, pts[1].y))

        fun at(i: Int): TimedPoint = pts[i.coerceIn(0, pts.lastIndex)]
        val out = ArrayList<PointF>()
        for (seg in 0 until pts.lastIndex) {
            val p0 = at(seg - 1); val p1 = at(seg); val p2 = at(seg + 1); val p3 = at(seg + 2)
            val segLen = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat().coerceAtLeast(1e-3f)
            val steps = max(2, (segLen / stepPx).toInt())
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                out.add(catmullRomPoint(p0, p1, p2, p3, t))
            }
        }
        out.add(PointF(pts.last().x, pts.last().y))
        return out
    }

    private fun catmullRomPoint(p0: TimedPoint, p1: TimedPoint, p2: TimedPoint, p3: TimedPoint, t: Float): PointF {
        val t2 = t * t
        val t3 = t2 * t
        fun blend(a: Float, b: Float, c: Float, d: Float): Float =
            0.5f * (
                (2f * b) +
                (-a + c) * t +
                (2f * a - 5f * b + 4f * c - d) * t2 +
                (-a + 3f * b - 3f * c + d) * t3
            )
        return PointF(blend(p0.x, p1.x, p2.x, p3.x), blend(p0.y, p1.y, p2.y, p3.y))
    }

    // ------------------------------------------------------------------------------------- stage 3

    /** Instantaneous speed (px/ms) between consecutive DENOISED points, one value per segment. */
    private fun localSpeeds(points: List<TimedPoint>): List<Float> {
        val speeds = ArrayList<Float>(points.size - 1)
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val dist = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            val dt = max(1L, b.tMs - a.tMs).toFloat()
            speeds.add(dist / dt)
        }
        return speeds
    }

    /** Maps the resampled (dense) polyline to a per-point width, blending speed-based pressure + end taper. */
    private fun widthsForResampled(resampled: List<PointF>, denoised: List<TimedPoint>, segSpeeds: List<Float>): FloatArray {
        val n = resampled.size
        val widths = FloatArray(n)
        // Map each resampled point back to its nearest original segment for a speed value (monotonic walk).
        val totalOriginalLen = run {
            var s = 0.0
            for (i in 0 until denoised.size - 1) {
                s += hypot((denoised[i + 1].x - denoised[i].x).toDouble(), (denoised[i + 1].y - denoised[i].y).toDouble())
            }
            s.toFloat().coerceAtLeast(1e-3f)
        }
        var cursorIdx = 0
        var cursorLen = 0f
        val resampledCum = FloatArray(n)
        for (i in 1 until n) {
            resampledCum[i] = resampledCum[i - 1] + hypot(
                (resampled[i].x - resampled[i - 1].x).toDouble(),
                (resampled[i].y - resampled[i - 1].y).toDouble()
            ).toFloat()
        }
        val totalResampledLen = resampledCum.last().coerceAtLeast(1e-3f)

        for (i in 0 until n) {
            val targetLen = resampledCum[i] / totalResampledLen * totalOriginalLen
            while (cursorIdx < segSpeeds.size - 1) {
                val segLen = hypot(
                    (denoised[cursorIdx + 1].x - denoised[cursorIdx].x).toDouble(),
                    (denoised[cursorIdx + 1].y - denoised[cursorIdx].y).toDouble()
                ).toFloat()
                if (cursorLen + segLen >= targetLen) break
                cursorLen += segLen
                cursorIdx++
            }
            val speed = segSpeeds.getOrElse(cursorIdx) { segSpeeds.lastOrNull() ?: SPEED_LOW_PX_MS }
            val speedT = ((speed - SPEED_LOW_PX_MS) / (SPEED_HIGH_PX_MS - SPEED_LOW_PX_MS)).coerceIn(0f, 1f)
            // Slow = thick, fast = thin (natural pen pressure): invert speedT.
            val pressureWidth = MAX_STROKE_WIDTH - speedT * (MAX_STROKE_WIDTH - MIN_STROKE_WIDTH)

            // End taper: scale down near the very start/end of the stroke (pen touch-down / lift-off).
            val posFrac = resampledCum[i] / totalResampledLen
            val taperStart = (posFrac / TAPER_FRACTION).coerceIn(0f, 1f)
            val taperEnd = ((1f - posFrac) / TAPER_FRACTION).coerceIn(0f, 1f)
            val taper = min(taperStart, taperEnd)
            // Ease the taper (smoothstep) so it feels gradual, not linear/abrupt.
            val eased = taper * taper * (3f - 2f * taper)
            widths[i] = (MIN_STROKE_WIDTH * 0.35f + pressureWidth * 0.65f) * (0.25f + 0.75f * eased)
        }
        return widths
    }

    /**
     * Draws the resampled centreline as a sequence of short, overlapping variable-width round-capped
     * segments. Overlapping round caps at each join is what gives a continuous ink look (no visible seams)
     * while still allowing the width to vary smoothly along the stroke, which a single Paint.strokeWidth
     * cannot do.
     */
    private fun drawTaperedStroke(canvas: Canvas, pts: List<PointF>, widths: FloatArray, colorArgb: Int) {
        if (pts.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorArgb
        }
        for (i in 0 until pts.size - 1) {
            paint.strokeWidth = ((widths[i] + widths[i + 1]) / 2f).coerceAtLeast(0.6f)
            canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, paint)
        }
    }
}
