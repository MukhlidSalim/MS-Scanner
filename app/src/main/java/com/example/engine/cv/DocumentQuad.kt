package com.example.engine.cv

import android.graphics.PointF
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max

data class DocumentQuad(
    val topLeft: PointF = PointF(0.08f, 0.08f),
    val topRight: PointF = PointF(0.92f, 0.08f),
    val bottomRight: PointF = PointF(0.92f, 0.92f),
    val bottomLeft: PointF = PointF(0.08f, 0.92f)
) {
    companion object {
        fun defaultQuad(): DocumentQuad {
            return DocumentQuad(
                PointF(0.06f, 0.06f),
                PointF(0.94f, 0.06f),
                PointF(0.94f, 0.94f),
                PointF(0.06f, 0.94f)
            )
        }

        fun fullQuad(): DocumentQuad {
            return DocumentQuad(
                PointF(0f, 0f),
                PointF(1f, 0f),
                PointF(1f, 1f),
                PointF(0f, 1f)
            )
        }

        fun fromJson(jsonStr: String): DocumentQuad {
            if (jsonStr.isBlank()) return defaultQuad()
            return try {
                val obj = JSONObject(jsonStr)
                DocumentQuad(
                    topLeft = PointF(obj.getDouble("tlX").toFloat(), obj.getDouble("tlY").toFloat()),
                    topRight = PointF(obj.getDouble("trX").toFloat(), obj.getDouble("trY").toFloat()),
                    bottomRight = PointF(obj.getDouble("brX").toFloat(), obj.getDouble("brY").toFloat()),
                    bottomLeft = PointF(obj.getDouble("blX").toFloat(), obj.getDouble("blY").toFloat())
                )
            } catch (e: Exception) {
                defaultQuad()
            }
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("tlX", topLeft.x.toDouble())
            put("tlY", topLeft.y.toDouble())
            put("trX", topRight.x.toDouble())
            put("trY", topRight.y.toDouble())
            put("brX", bottomRight.x.toDouble())
            put("brY", bottomRight.y.toDouble())
            put("blX", bottomLeft.x.toDouble())
            put("blY", bottomLeft.y.toDouble())
        }.toString()
    }

    fun toAbsolutePoints(width: Float, height: Float): FloatArray {
        return floatArrayOf(
            topLeft.x * width, topLeft.y * height,
            topRight.x * width, topRight.y * height,
            bottomRight.x * width, bottomRight.y * height,
            bottomLeft.x * width, bottomLeft.y * height
        )
    }

    fun targetDimensions(width: Float, height: Float): Pair<Int, Int> {
        val pts = toAbsolutePoints(width, height)
        val topWidth = hypot(pts[2] - pts[0], pts[3] - pts[1])
        val bottomWidth = hypot(pts[4] - pts[6], pts[5] - pts[7])
        val targetWidth = max(topWidth, bottomWidth).coerceAtLeast(100f).toInt()

        val leftHeight = hypot(pts[6] - pts[0], pts[7] - pts[1])
        val rightHeight = hypot(pts[4] - pts[2], pts[5] - pts[3])
        val targetHeight = max(leftHeight, rightHeight).coerceAtLeast(100f).toInt()

        return Pair(targetWidth, targetHeight)
    }
}
