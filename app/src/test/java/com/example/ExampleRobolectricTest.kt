package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentEntity
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentDetector
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DocScan Pro", appName)
    }

    @Test
    fun testDocumentQuadSerialization() {
        val original = DocumentQuad(
            topLeft = PointF(0.1f, 0.1f),
            topRight = PointF(0.9f, 0.1f),
            bottomRight = PointF(0.9f, 0.9f),
            bottomLeft = PointF(0.1f, 0.9f)
        )
        val json = original.toJson()
        val parsed = DocumentQuad.fromJson(json)

        assertEquals(original.topLeft.x, parsed.topLeft.x, 0.001f)
        assertEquals(original.topRight.y, parsed.topRight.y, 0.001f)
    }

    @Test
    fun testDocumentDetectorWithBitmap() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val detector = DocumentDetector()
        val result = detector.processFrame(bitmap)
        assertNotNull(result)
        assertNotNull(result.quad)
    }

    @Test
    fun testQualityAnalyzer() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val report = ImageProcessor.analyzeQuality(bitmap)
        assertNotNull(report)
        assertTrue(report.score in 0..100)
    }

    @Test
    fun testDocumentEntityDefaults() {
        val doc = DocumentEntity(title = "Receipt Jan 2026")
        assertEquals("Receipt Jan 2026", doc.title)
        assertEquals("Default", doc.folderName)
        assertEquals(DocumentCategory.OTHER.name, doc.category)
        assertFalse(doc.isTrash)
        assertFalse(doc.isFavorite)
    }
}
