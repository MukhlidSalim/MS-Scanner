package com.example

import android.content.Context
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CompressionPreset
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentEntity
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.engine.pdf.PdfEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests that do not depend on real pixel rendering.
 * Image processing / detection accuracy tests need real graphics and live in androidTest
 * (DocumentDetectionAccuracyTest): Robolectric's default graphics mode does not draw Canvas content,
 * so detection assertions here would test nothing (and previously did not even compile: suspend
 * functions were called outside a coroutine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("MS Scanner", context.getString(R.string.app_name))
    }

    @Test
    fun testDocumentQuadSerialization() {
        val original = DocumentQuad(
            topLeft = PointF(0.1f, 0.1f),
            topRight = PointF(0.9f, 0.1f),
            bottomRight = PointF(0.9f, 0.9f),
            bottomLeft = PointF(0.1f, 0.9f)
        )
        val parsed = DocumentQuad.fromJson(original.toJson())
        assertEquals(original.topLeft.x, parsed.topLeft.x, 0.001f)
        assertEquals(original.topRight.y, parsed.topRight.y, 0.001f)
        assertEquals(original.bottomRight.x, parsed.bottomRight.x, 0.001f)
    }

    @Test
    fun testQuadValidation() {
        val validQuad = DocumentQuad(PointF(0.1f, 0.1f), PointF(0.9f, 0.1f), PointF(0.9f, 0.9f), PointF(0.1f, 0.9f))
        assertTrue(ImageProcessor.isQuadValid(validQuad))
        val tinyQuad = DocumentQuad(PointF(0.48f, 0.48f), PointF(0.52f, 0.48f), PointF(0.52f, 0.52f), PointF(0.48f, 0.52f))
        assertFalse(ImageProcessor.isQuadValid(tinyQuad))
    }

    @Test
    fun testInvalidCrossingQuadIsRejected() {
        val crossing = DocumentQuad(PointF(0.1f, 0.1f), PointF(0.9f, 0.9f), PointF(0.1f, 0.9f), PointF(0.9f, 0.1f))
        assertFalse(ImageProcessor.isQuadValid(crossing))
    }

    @Test
    fun testPdfEngineSizeEstimation() {
        val lowEst = PdfEngine.estimatePdfSizeBytes(3, CompressionPreset.LOW)
        val maxEst = PdfEngine.estimatePdfSizeBytes(3, CompressionPreset.MAXIMUM)
        assertTrue(lowEst < maxEst)
        assertTrue(PdfEngine.formatEstimatedSize(lowEst).isNotEmpty())
    }

    @Test
    fun testSafeFileNameKeepsArabic() {
        assertEquals("عقد إيجار", PdfEngine.safeFileName("عقد إيجار"))
        assertEquals("a_b_c", PdfEngine.safeFileName("a/b:c"))
        assertEquals("Document", PdfEngine.safeFileName("   "))
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
