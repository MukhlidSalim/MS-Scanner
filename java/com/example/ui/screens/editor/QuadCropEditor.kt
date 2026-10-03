package com.example.ui.screens.editor

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageFit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the finger is currently holding. */
private sealed interface DragTarget {
    data class Corner(val index: Int) : DragTarget
    /** Edge from corner [index] to corner (index + 1) % 4. */
    data class Edge(val index: Int) : DragTarget
}

/**
 * Manual quad editor: continuous Touch -> Drag -> Live update -> Release.
 *
 * Root causes fixed versus the previous implementation:
 *  - pointerInput was keyed on `quad`, so every live update restarted the gesture coroutine and
 *    cancelled the drag after one move event (felt like "tap repeatedly"). Here the gesture block is
 *    keyed on Unit and reads the latest quad / layout through rememberUpdatedState.
 *  - Positions were integrated from per-event deltas (drift + clamping loss). Here the new position is
 *    computed from the ABSOLUTE finger position and the offset captured at touch-down.
 *  - Hit radius was normalized by width only (wrong on the Y axis). Hit testing now happens in view
 *    pixels with dp-based radii (corner 36 dp, edge 28 dp), corners have priority, edges use their
 *    middle 70% so the two targets never compete.
 *  - A non-convex intermediate position no longer freezes the handle: the last valid quad is kept and
 *    tracking continues, so the handle follows again as soon as the shape becomes valid.
 *
 * [quad] is normalized to [image] (the DISPLAYED bitmap). The caller converts to/from raw coordinates.
 */
@Composable
fun QuadCropEditor(
    image: ImageBitmap,
    quad: DocumentQuad,
    onQuadChange: (DocumentQuad) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF34D399),
    showLoupe: Boolean = true
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val paddingPx = with(density) { 28.dp.toPx() }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(image, boxSize) {
        if (boxSize.width <= 0 || boxSize.height <= 0) null
        else ImageFit(image.width, image.height, boxSize.width.toFloat(), boxSize.height.toFloat(), paddingPx)
    }

    var active by remember { mutableStateOf<DragTarget?>(null) }
    var fingerPos by remember { mutableStateOf<Offset?>(null) }

    val latestQuad by rememberUpdatedState(quad)
    val latestFit by rememberUpdatedState(fit)
    val latestOnChange by rememberUpdatedState(onQuadChange)
    val latestOnStart by rememberUpdatedState(onDragStart)
    val latestOnEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                val cornerRadius = 36.dp.toPx()
                val edgeRadius = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val f = latestFit ?: return@awaitEachGesture
                    val startQuad = latestQuad
                    val pts = startQuad.points().map { Offset(f.toViewX(it.x), f.toViewY(it.y)) }

                    val target = hitTest(down.position, pts, cornerRadius, edgeRadius) ?: return@awaitEachGesture
                    down.consume()
                    active = target
                    fingerPos = down.position
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    latestOnStart()

                    // Offset between finger and handle at touch-down, so the handle never jumps.
                    val grab: Offset = when (target) {
                        is DragTarget.Corner -> down.position - pts[target.index]
                        is DragTarget.Edge -> Offset.Zero
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val pos = change.position
                            fingerPos = pos
                            val candidate = when (target) {
                                is DragTarget.Corner -> {
                                    val p = pos - grab
                                    startQuad.withPoint(
                                        target.index,
                                        PointF(f.toNormX(p.x).coerceIn(0f, 1f), f.toNormY(p.y).coerceIn(0f, 1f))
                                    )
                                }
                                is DragTarget.Edge -> moveEdge(startQuad, target.index, pos - down.position, pts, f)
                            }
                            if (candidate.isConvex() && candidate.minSide() >= MIN_SIDE) {
                                latestOnChange(candidate)
                            }
                            change.consume()
                        }
                    } finally {
                        active = null
                        fingerPos = null
                        latestOnEnd()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val f = fit ?: return@Canvas
            val left = f.left
            val top = f.top
            val rw = f.renderWidth
            val rh = f.renderHeight

            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(rw.roundToInt(), rh.roundToInt()),
                filterQuality = FilterQuality.Medium
            )

            val pts = quad.points().map { Offset(f.toViewX(it.x), f.toViewY(it.y)) }
            val quadPath = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                close()
            }
            // Dim everything outside the crop.
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + rw, top + rh))
                addPath(quadPath)
            }
            drawPath(dim, Color.Black.copy(alpha = 0.45f))
            drawPath(quadPath, accent, style = Stroke(width = 2.5.dp.toPx()))

            val activeTarget = active
            // Edge handles (pills at midpoints).
            for (i in 0 until 4) {
                val a = pts[i]
                val b = pts[(i + 1) % 4]
                val mid = (a + b) / 2f
                val isActive = activeTarget is DragTarget.Edge && activeTarget.index == i
                drawCircle(Color.White, radius = if (isActive) 9.dp.toPx() else 7.dp.toPx(), center = mid)
                drawCircle(accent, radius = if (isActive) 6.dp.toPx() else 4.5.dp.toPx(), center = mid)
            }
            // Corner handles.
            for (i in 0 until 4) {
                val isActive = activeTarget is DragTarget.Corner && activeTarget.index == i
                drawCircle(accent.copy(alpha = 0.25f), radius = if (isActive) 22.dp.toPx() else 18.dp.toPx(), center = pts[i])
                drawCircle(Color.White, radius = if (isActive) 12.dp.toPx() else 10.dp.toPx(), center = pts[i])
                drawCircle(accent, radius = if (isActive) 8.dp.toPx() else 6.5.dp.toPx(), center = pts[i])
            }

            // Magnifier: shows the image around the handle being dragged (not under the finger).
            val touch = fingerPos
            if (showLoupe && activeTarget != null && touch != null) {
                val focus: Offset = when (activeTarget) {
                    is DragTarget.Corner -> pts[activeTarget.index]
                    is DragTarget.Edge -> (pts[activeTarget.index] + pts[(activeTarget.index + 1) % 4]) / 2f
                }
                val radius = 56.dp.toPx()
                val gap = 72.dp.toPx()
                val cx = touch.x.coerceIn(radius + 8f, this.size.width - radius - 8f)
                var cy = touch.y - gap - radius
                if (cy < radius + 8f) cy = touch.y + gap + radius // flip below the finger near the top
                cy = cy.coerceIn(radius + 8f, this.size.height - radius - 8f)
                val center = Offset(cx, cy)

                val zoom = 2.5f
                val srcSide = (2 * radius / (f.scale * zoom)).coerceAtLeast(8f)
                val fx = f.toNormX(focus.x) * image.width
                val fy = f.toNormY(focus.y) * image.height
                val srcLeft = (fx - srcSide / 2f)
                val srcTop = (fy - srcSide / 2f)
                val clip = Path().apply { addOval(Rect(center, radius)) }
                drawCircle(Color.Black, radius = radius, center = center)
                clipPath(clip) {
                    // Clamp the source window inside the bitmap and shift the destination accordingly.
                    val sl = srcLeft.coerceIn(0f, max(0f, image.width - srcSide))
                    val st = srcTop.coerceIn(0f, max(0f, image.height - srcSide))
                    val side = min(srcSide, min(image.width.toFloat(), image.height.toFloat()))
                    val pxPerSrc = (2 * radius) / srcSide
                    val dstLeft = center.x - radius + (sl - srcLeft) * pxPerSrc
                    val dstTop = center.y - radius + (st - srcTop) * pxPerSrc
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(sl.roundToInt(), st.roundToInt()),
                        srcSize = IntSize(side.roundToInt().coerceAtLeast(1), side.roundToInt().coerceAtLeast(1)),
                        dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
                        dstSize = IntSize((side * pxPerSrc).roundToInt().coerceAtLeast(1), (side * pxPerSrc).roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low
                    )
                    // Quad lines inside the loupe, in loupe space.
                    fun toLoupe(p: Offset): Offset {
                        val ix = f.toNormX(p.x) * image.width
                        val iy = f.toNormY(p.y) * image.height
                        return Offset(center.x + (ix - fx) * pxPerSrc, center.y + (iy - fy) * pxPerSrc)
                    }
                    val lp = Path().apply {
                        val l = pts.map { toLoupe(it) }
                        moveTo(l[0].x, l[0].y)
                        for (i in 1 until 4) lineTo(l[i].x, l[i].y)
                        close()
                    }
                    drawPath(lp, accent, style = Stroke(width = 1.5.dp.toPx()))
                }
                drawCircle(accent, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
                val ch = 10.dp.toPx()
                val focusInLoupe = Offset(center.x, center.y)
                drawLine(Color.White, focusInLoupe - Offset(ch, 0f), focusInLoupe + Offset(ch, 0f), 1.5.dp.toPx())
                drawLine(Color.White, focusInLoupe - Offset(0f, ch), focusInLoupe + Offset(0f, ch), 1.5.dp.toPx())
            }
        }
    }
}

/** Minimum side length (normalized) while dragging, prevents collapsing the quad. */
private const val MIN_SIDE = 0.04f

private fun hitTest(p: Offset, pts: List<Offset>, cornerRadius: Float, edgeRadius: Float): DragTarget? {
    var bestCorner = -1
    var bestDist = Float.MAX_VALUE
    for (i in 0 until 4) {
        val d = (pts[i] - p).getDistance()
        if (d <= cornerRadius && d < bestDist) {
            bestDist = d
            bestCorner = i
        }
    }
    if (bestCorner >= 0) return DragTarget.Corner(bestCorner)

    var bestEdge = -1
    bestDist = Float.MAX_VALUE
    for (i in 0 until 4) {
        val a = pts[i]
        val b = pts[(i + 1) % 4]
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        if (len2 <= 1f) continue
        val t = ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2
        if (t < 0.15f || t > 0.85f) continue
        val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
        val d = (p - proj).getDistance()
        if (d <= edgeRadius && d < bestDist) {
            bestDist = d
            bestEdge = i
        }
    }
    return if (bestEdge >= 0) DragTarget.Edge(bestEdge) else null
}

/**
 * Moves edge [index] along its own normal (in view space) by the finger's total displacement
 * projected on that normal. Both endpoints move together; other corners stay fixed.
 */
private fun moveEdge(start: DocumentQuad, index: Int, delta: Offset, viewPts: List<Offset>, f: ImageFit): DocumentQuad {
    val a = viewPts[index]
    val b = viewPts[(index + 1) % 4]
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val d = delta.x * nx + delta.y * ny
    val na = Offset(a.x + nx * d, a.y + ny * d)
    val nb = Offset(b.x + nx * d, b.y + ny * d)
    return start
        .withPoint(index, PointF(f.toNormX(na.x).coerceIn(0f, 1f), f.toNormY(na.y).coerceIn(0f, 1f)))
        .withPoint((index + 1) % 4, PointF(f.toNormX(nb.x).coerceIn(0f, 1f), f.toNormY(nb.y).coerceIn(0f, 1f)))
}
