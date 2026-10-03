package com.example.engine.cv

import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * All coordinate conversions of the scanner live here, so every stage uses the same math:
 *
 *   Camera frame (sensor) --rotationDegrees--> Oriented analysis crop (normalized quad)
 *        --FILL_CENTER / FIT_CENTER (+ optional mirror)--> PreviewView pixels (overlay)
 *   Oriented analysis crop  ==  saved photo field of view (same ViewPort)  --> upright still (normalized)
 *   Upright raw image (normalized quad)  --[ImageFit]-->  Crop editor pixels (and back for gestures)
 *   Upright raw image + quad --warp--> rectified --rotate(user)--> final image
 */
object QuadCoordinateMapper {

    /**
     * Maps a quad normalized to an oriented frame of [frameW] x [frameH] onto a view of
     * [viewW] x [viewH] pixels. Returns 8 floats (TL, TR, BR, BL; x,y each).
     *
     * [fillCenter] must match PreviewView.scaleType (FILL_CENTER is the CameraX default).
     * [mirror] must be true for the front camera, because PreviewView mirrors its preview while
     * ImageAnalysis frames are not mirrored.
     *
     * When a ViewPort is used, frame aspect == view aspect and this reduces to a pure scale.
     */
    fun frameToView(
        quad: DocumentQuad,
        frameW: Int,
        frameH: Int,
        viewW: Float,
        viewH: Float,
        fillCenter: Boolean = true,
        mirror: Boolean = false
    ): FloatArray {
        val fw = max(1, frameW).toFloat()
        val fh = max(1, frameH).toFloat()
        val scale = if (fillCenter) max(viewW / fw, viewH / fh) else min(viewW / fw, viewH / fh)
        val rw = fw * scale
        val rh = fh * scale
        val ox = (viewW - rw) / 2f
        val oy = (viewH - rh) / 2f
        val src = if (mirror) quad.mirroredHorizontally() else quad
        val pts = src.points()
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = ox + pts[i].x * rw
            out[2 * i + 1] = oy + pts[i].y * rh
        }
        return out
    }
}

/**
 * Aspect-preserving "fit inside with padding" transform used by the crop editor.
 * [padding] keeps corner handles reachable when the document touches the image border.
 */
class ImageFit(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val padding: Float
) {
    val scale: Float = min(
        (viewWidth - 2 * padding).coerceAtLeast(1f) / max(1, imageWidth),
        (viewHeight - 2 * padding).coerceAtLeast(1f) / max(1, imageHeight)
    )
    val renderWidth: Float = imageWidth * scale
    val renderHeight: Float = imageHeight * scale
    val left: Float = (viewWidth - renderWidth) / 2f
    val top: Float = (viewHeight - renderHeight) / 2f

    fun toViewX(nx: Float): Float = left + nx * renderWidth
    fun toViewY(ny: Float): Float = top + ny * renderHeight
    fun toNormX(vx: Float): Float = (vx - left) / renderWidth
    fun toNormY(vy: Float): Float = (vy - top) / renderHeight

    fun toView(p: PointF): PointF = PointF(toViewX(p.x), toViewY(p.y))
    fun toNormalized(vx: Float, vy: Float): PointF = PointF(toNormX(vx), toNormY(vy))
}
