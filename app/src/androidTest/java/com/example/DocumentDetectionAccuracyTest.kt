
package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentDetector
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * Detection accuracy regression suite on synthetic scenes with KNOWN corners (real graphics: runs on a
 * device / emulator: ./gradlew connectedDebugAndroidTest). Every change to the detector must keep these
 * green. Error = mean corner distance in % of the image diagonal.
 */
@RunWith(AndroidJUnit4::class)
class DocumentDetectionAccuracyTest {

    private val w = 800
    private val h = 1000

    /** Draws a white page with text lines at [corners] (pixels, TL,TR,BR,BL) on a background. */
    private fun scene(corners: List<PointF>, background: Int, paper: Int = Color.WHITE, shadow: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        // text-like lines inside the page (they must NOT be taken as the border)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); strokeWidth = 4f }
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()
        for (i in -6..6) canvas.drawLine(cx - 180f, cy + i * 40f, cx + 160f, cy + i * 40f, ink)
        if (shadow) {
            canvas.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(70, 0, 0, 0) })
        }
        return bmp
    }

    private fun errorPercent(q: DocumentQuad, expected: List<PointF>): Double {
        val got = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft).map { PointF(it.x * w, it.y * h) }
        val diag = hypot(w.toDouble(), h.toDouble())
        return got.indices.map { hypot((got[it].x - expected[it].x).toDouble(), (got[it].y - expected[it].y).toDouble()) }
            .average() / diag * 100.0
    }

    private fun assertDetected(corners: List<PointF>, bg: Int, maxErrorPct: Double, shadow: Boolean = false) {
        val bmp = scene(corners, bg, shadow = shadow)
        try {
            val d = DocumentDetector.detect(bmp)
            assertNotNull("document not detected", d)
            val err = errorPercent(d!!.quad, corners)
            assertTrue("corner error ${"%.2f".format(err)}% > $maxErrorPct%", err <= maxErrorPct)
        } finally {
            bmp.recycle()
        }
    }

    private fun pts(vararg xy: Float) = xy.toList().chunked(2).map { PointF(it[0], it[1]) }

    @Test fun straightPageOnDarkDesk() =
        assertDetected(pts(120f, 100f, 680f, 100f, 680f, 900f, 120f, 900f), Color.rgb(60, 50, 45), 1.5)

    @Test fun strongPerspective() =
        assertDetected(pts(180f, 120f, 620f, 90f, 740f, 930f, 60f, 880f), Color.rgb(70, 70, 75), 2.0)

    @Test fun rotatedPage() {
        val base = pts(200f, 150f, 600f, 150f, 600f, 850f, 200f, 850f)
        val m = Matrix().apply { setRotate(18f, 400f, 500f) }
        val arr = base.flatMap { listOf(it.x, it.y) }.toFloatArray().also { m.mapPoints(it) }
        assertDetected(arr.toList().chunked(2).map { PointF(it[0], it[1]) }, Color.rgb(40, 40, 40), 2.0)
    }

    @Test fun lowContrastLightDesk() =
        assertDetected(pts(120f, 100f, 680f, 100f, 680f, 900f, 120f, 900f), Color.rgb(175, 170, 160), 2.5)

    @Test fun pageWithShadow() =
        assertDetected(pts(120f, 100f, 680f, 100f, 680f, 900f, 120f, 900f), Color.rgb(90, 80, 70), 2.5, shadow = true)

    @Test fun smallIdCard() =
        assertDetected(pts(250f, 400f, 550f, 400f, 550f, 590f, 250f, 590f), Color.rgb(50, 60, 70), 2.0)

    @Test fun emptySceneIsNotADocument() {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
        try {
            assertNull(DocumentDetector.detect(bmp))
        } finally {
            bmp.recycle()
        }
    }

    @Test fun warpProducesUprightPage() {
        val corners = pts(180f, 120f, 620f, 90f, 740f, 930f, 60f, 880f)
        val bmp = scene(corners, Color.rgb(70, 70, 75))
        try {
            val q = DocumentQuad(
                PointF(corners[0].x / w, corners[0].y / h), PointF(corners[1].x / w, corners[1].y / h),
                PointF(corners[2].x / w, corners[2].y / h), PointF(corners[3].x / w, corners[3].y / h)
            )
            val out = DocumentPipeline.warp(bmp, q)
            assertTrue(out.height > out.width) // portrait page stays portrait
            // corners of the warped page must be paper (white), not desk
            val c = out.getPixel(3, 3)
            assertTrue(Color.red(c) > 200 && Color.green(c) > 200)
            if (out !== bmp) out.recycle()
        } finally {
            bmp.recycle()
        }
    }

    @Test fun filtersKeepSizeAndQualityReportIsBounded() = runBlocking {
        val bmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        for (f in FilterType.values()) {
            val res = ImageProcessor.applyFilter(bmp, f)
            assertEquals(120, res.width)
            assertEquals(160, res.height)
            if (res !== bmp) res.recycle()
        }
        val report = ImageProcessor.analyzeQuality(bmp)
        assertTrue(report.score in 0..100)
        bmp.recycle()
    }

    /** White sheet on a near-white desk with a printed table inside: the SHEET must win, not the table. */
    @Test fun whiteSheetOnWhiteDeskWithTable() {
        val corners = pts(90f, 100f, 710f, 85f, 720f, 920f, 80f, 905f)
        val bmp = scene(corners, Color.rgb(222, 222, 220), paper = Color.rgb(250, 250, 250))
        try {
            val c = Canvas(bmp)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); style = Paint.Style.STROKE; strokeWidth = 3f }
            c.drawRect(150f, 300f, 650f, 700f, frame)
            val d = DocumentDetector.detect(bmp)
            assertNotNull("document not detected", d)
            val err = errorPercent(d!!.quad, corners)
            assertTrue("corner error ${"%.2f".format(err)}% > 1.5%", err <= 1.5)
        } finally {
            bmp.recycle()
        }
    }

    /** Text printed with a 3° skew is measured and corrected (sign = getRotationMatrix2D convention). */
    @Test fun deskewMeasuresTextSkew() {
        val page = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(page)
            c.drawColor(Color.rgb(245, 245, 245))
            c.save()
            c.rotate(3f, 425f, 550f) // clockwise on screen
            val ink = Paint().apply { color = Color.rgb(40, 40, 40) }
            var y = 90f
            while (y < 1010f) {
                var x = 70f
                while (x < 760f) { c.drawRect(x, y, x + 60f, y + 10f, ink); x += 80f }
                y += 30f
            }
            c.restore()
            val angle = com.example.engine.cv.PageStraightener.estimateSkew(page)
            assertTrue("measured $angle°", kotlin.math.abs(kotlin.math.abs(angle) - 3.0) <= 0.3)
        } finally {
            page.recycle()
        }
    }

    @Test fun deskewLeavesStraightPageAlone() {
        val page = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(page)
            c.drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK }
            var y = 90f
            while (y < 1010f) { c.drawRect(70f, y, 760f, y + 10f, ink); y += 30f }
            assertEquals(0.0, com.example.engine.cv.PageStraightener.estimateSkew(page), 0.0)
        } finally {
            page.recycle()
        }
    }
}
