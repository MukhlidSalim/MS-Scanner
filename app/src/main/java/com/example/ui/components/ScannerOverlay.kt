package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.cv.DetectionResult
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight

@Composable
fun ScannerOverlay(
    detection: DetectionResult,
    isGridVisible: Boolean,
    modifier: Modifier = Modifier
) {
    // Pulse animation for detected document quad
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Grid overlay if enabled
            if (isGridVisible) {
                val gridColor = Color.White.copy(alpha = 0.2f)
                val stroke = 1.5f
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
            }

            // 2. Draw Quad boundary
            val q = detection.quad
            val tl = Offset(q.topLeft.x * w, q.topLeft.y * h)
            val tr = Offset(q.topRight.x * w, q.topRight.y * h)
            val br = Offset(q.bottomRight.x * w, q.bottomRight.y * h)
            val bl = Offset(q.bottomLeft.x * w, q.bottomLeft.y * h)

            val quadColor = if (detection.isStable) {
                EmeraldLight.copy(alpha = alphaAnim)
            } else if (detection.isDetected) {
                CyanScan.copy(alpha = alphaAnim)
            } else {
                Color.White.copy(alpha = 0.4f)
            }

            // Boundary polygon
            val path = Path().apply {
                moveTo(tl.x, tl.y)
                lineTo(tr.x, tr.y)
                lineTo(br.x, br.y)
                lineTo(bl.x, bl.y)
                close()
            }

            // Subtle fill
            drawPath(path, color = quadColor.copy(alpha = if (detection.isDetected) 0.08f else 0.03f))
            drawPath(path, color = quadColor, style = Stroke(width = if (detection.isStable) 4f else 2.5f))

            // Corner bracket accents
            val bracketLen = 32f
            val bracketColor = if (detection.isStable) EmeraldLight else CyanScan
            val bStroke = 6f

            // Top-Left bracket
            drawLine(bracketColor, tl, Offset(tl.x + bracketLen, tl.y), bStroke)
            drawLine(bracketColor, tl, Offset(tl.x, tl.y + bracketLen), bStroke)
            // Top-Right bracket
            drawLine(bracketColor, tr, Offset(tr.x - bracketLen, tr.y), bStroke)
            drawLine(bracketColor, tr, Offset(tr.x, tr.y + bracketLen), bStroke)
            // Bottom-Right bracket
            drawLine(bracketColor, br, Offset(br.x - bracketLen, br.y), bStroke)
            drawLine(bracketColor, br, Offset(br.x, br.y - bracketLen), bStroke)
            // Bottom-Left bracket
            drawLine(bracketColor, bl, Offset(bl.x + bracketLen, bl.y), bStroke)
            drawLine(bracketColor, bl, Offset(bl.x, bl.y - bracketLen), bStroke)
        }

        // Real-Time Guidance Toast Chip
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .testTag("scanner_guidance_chip"),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.75f),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (detection.stabilityProgress > 0f && !detection.isStable) {
                    CircularProgressIndicator(
                        progress = { detection.stabilityProgress },
                        modifier = Modifier.size(16.dp),
                        color = EmeraldLight,
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (detection.isStable) EmeraldLight else if (detection.isDetected) CyanScan else Color.Gray,
                                shape = CircleShape
                            )
                    )
                }

                Text(
                    text = detection.guidanceEn,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
