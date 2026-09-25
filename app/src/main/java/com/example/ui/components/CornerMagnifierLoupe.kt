package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight

@Composable
fun CornerMagnifierLoupe(
    bitmap: Bitmap?,
    touchXNormalized: Float,
    touchYNormalized: Float,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) return

    val loupeSizeDp = 100.dp

    Surface(
        modifier = modifier
            .size(loupeSizeDp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .border(3.dp, EmeraldLight, CircleShape),
        color = Color.Black
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val bmpW = bitmap.width
            val bmpH = bitmap.height

            // Calculate source crop around the touched normalized coordinate
            val zoom = 2.5f
            val sampleW = (bmpW / zoom).toInt()
            val sampleH = (bmpH / zoom).toInt()

            val centerX = (touchXNormalized * bmpW).toInt()
            val centerY = (touchYNormalized * bmpH).toInt()

            val srcX = (centerX - sampleW / 2).coerceIn(0, (bmpW - sampleW).coerceAtLeast(0))
            val srcY = (centerY - sampleH / 2).coerceIn(0, (bmpH - sampleH).coerceAtLeast(0))

            val actualSampleW = sampleW.coerceAtMost(bmpW - srcX)
            val actualSampleH = sampleH.coerceAtMost(bmpH - srcY)

            if (actualSampleW > 0 && actualSampleH > 0) {
                val imageBitmap = bitmap.asImageBitmap()
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(actualSampleW, actualSampleH),
                    dstOffset = IntOffset(0, 0),
                    dstSize = IntSize(canvasW.toInt(), canvasH.toInt())
                )
            }

            // Crosshairs
            val center = Offset(canvasW / 2f, canvasH / 2f)
            val hairLen = 20f
            drawLine(CyanScan, Offset(center.x - hairLen, center.y), Offset(center.x + hairLen, center.y), 2f)
            drawLine(CyanScan, Offset(center.x, center.y - hairLen), Offset(center.x, center.y + hairLen), 2f)
        }
    }
}
