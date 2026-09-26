package com.example.engine.annotation

import android.content.Context
import android.graphics.*
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class StrokePoint(val x: Float, val y: Float)

data class DrawPath(
    val points: List<StrokePoint>,
    val color: Int,
    val strokeWidth: Float,
    val isHighlighter: Boolean = false,
    val isEraser: Boolean = false
)

enum class ShapeType {
    ARROW,
    RECTANGLE,
    CIRCLE
}

data class DrawShape(
    val type: ShapeType,
    val startX: Float, // normalized 0..1
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val color: Int,
    val strokeWidth: Float = 4f
)

data class RedactionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class PlacedSignature(
    val signatureBitmap: Bitmap,
    val x: Float, // 0f..1f normalized center
    val y: Float, // 0f..1f normalized center
    val scale: Float = 0.35f // relative to page width
)

data class PlacedText(
    val text: String,
    val x: Float, // 0f..1f normalized
    val y: Float, // 0f..1f normalized
    val color: Int = Color.BLACK,
    val textSize: Float = 48f
)

object AnnotationEngine {

    suspend fun saveSignatureBitmap(context: Context, signatureBitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "signatures").apply { if (!exists()) mkdirs() }
        val file = File(dir, "sig_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png")
        FileOutputStream(file).use { out ->
            signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file.absolutePath
    }

    /**
     * Permanently burns all annotations, signatures, shapes, and redaction boxes into the image bitmap
     */
    fun burnAnnotationsIntoBitmap(
        baseBitmap: Bitmap,
        paths: List<DrawPath>,
        redactions: List<RedactionRect>,
        placedSignatures: List<PlacedSignature>,
        placedTexts: List<PlacedText> = emptyList(),
        shapes: List<DrawShape> = emptyList(),
        brightness: Float = 0f,
        contrast: Float = 1f
    ): Bitmap {
        val output = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()

        val cm = ColorMatrix()
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + (brightness * 255f / 100f)
        val array = FloatArray(20)
        array[0] = scale; array[4] = translate
        array[6] = scale; array[9] = translate
        array[12] = scale; array[14] = translate
        array[18] = 1f
        cm.set(array)
        val paintBase = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(baseBitmap, 0f, 0f, paintBase)

        // 1. Draw pen and highlighter strokes
        for (dp in paths) {
            if (dp.points.size < 2) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dp.color
                strokeWidth = dp.strokeWidth * (w / 400f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (dp.isHighlighter) {
                    alpha = 110
                }
                if (dp.isEraser) {
                    color = Color.WHITE
                }
            }
            val path = Path()
            path.moveTo(dp.points[0].x * w, dp.points[0].y * h)
            for (i in 1 until dp.points.size) {
                path.lineTo(dp.points[i].x * w, dp.points[i].y * h)
            }
            canvas.drawPath(path, paint)
        }

        // 2. Draw geometric shapes (Arrow, Rectangle, Circle)
        for (s in shapes) {
            val sx = s.startX * w
            val sy = s.startY * h
            val ex = s.endX * w
            val ey = s.endY * h
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                strokeWidth = s.strokeWidth * (w / 400f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            when (s.type) {
                ShapeType.RECTANGLE -> {
                    val rect = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                    canvas.drawRoundRect(rect, 8f, 8f, paint)
                }
                ShapeType.CIRCLE -> {
                    val cx = (sx + ex) / 2f
                    val cy = (sy + ey) / 2f
                    val rx = kotlin.math.abs(ex - sx) / 2f
                    val ry = kotlin.math.abs(ey - sy) / 2f
                    val rect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                    canvas.drawOval(rect, paint)
                }
                ShapeType.ARROW -> {
                    canvas.drawLine(sx, sy, ex, ey, paint)
                    // Draw arrowhead
                    val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                    val arrowLen = 28f * (w / 400f)
                    val arrowAngle = Math.toRadians(25.0)

                    val x1 = (ex - arrowLen * cos(angle - arrowAngle)).toFloat()
                    val y1 = (ey - arrowLen * sin(angle - arrowAngle)).toFloat()
                    val x2 = (ex - arrowLen * cos(angle + arrowAngle)).toFloat()
                    val y2 = (ey - arrowLen * sin(angle + arrowAngle)).toFloat()

                    val headPath = Path().apply {
                        moveTo(ex, ey)
                        lineTo(x1, y1)
                        moveTo(ex, ey)
                        lineTo(x2, y2)
                    }
                    canvas.drawPath(headPath, paint)
                }
            }
        }

        // 3. Draw placed electronic signatures (transparent PNGs)
        val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (ps in placedSignatures) {
            val targetW = (w * ps.scale).toInt()
            val targetH = (targetW * (ps.signatureBitmap.height.toFloat() / ps.signatureBitmap.width.toFloat())).toInt()
            val centerX = ps.x * w
            val centerY = ps.y * h
            val rect = Rect(
                (centerX - targetW / 2).toInt(),
                (centerY - targetH / 2).toInt(),
                (centerX + targetW / 2).toInt(),
                (centerY + targetH / 2).toInt()
            )
            canvas.drawBitmap(ps.signatureBitmap, null, rect, sigPaint)
        }

        // 4. Draw permanent blackout redaction boxes
        val redactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        for (r in redactions) {
            val rectF = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
            canvas.drawRect(rectF, redactPaint)
        }

        // 5. Draw placed texts
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        for (pt in placedTexts) {
            textPaint.color = pt.color
            textPaint.textSize = pt.textSize * (w / 1080f)
            canvas.drawText(pt.text, pt.x * w, pt.y * h, textPaint)
        }

        return output
    }
}
