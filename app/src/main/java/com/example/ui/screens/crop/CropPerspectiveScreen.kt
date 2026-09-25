package com.example.ui.screens.crop

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.cv.DocumentDetector
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.ui.components.CornerMagnifierLoupe
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight
import com.example.ui.viewmodel.DocumentViewModel
import kotlinx.coroutines.launch
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropPerspectiveScreen(
    docId: Long,
    pageId: Long,
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val page = uiState.activePages.find { it.id == pageId }
    val coroutineScope = rememberCoroutineScope()

    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var quad by remember { mutableStateOf(DocumentQuad.defaultQuad()) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeDraggingCorner by remember { mutableStateOf<Int?>(null) } // 0: TL, 1: TR, 2: BR, 3: BL

    LaunchedEffect(pageId) {
        if (page != null) {
            val bmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath)
            rawBitmap = bmp
            if (page.cropQuadJson.isNotBlank()) {
                quad = DocumentQuad.fromJson(page.cropQuadJson)
            } else if (bmp != null) {
                // Auto detect
                val res = DocumentDetector().processFrame(bmp)
                quad = res.quad
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Crop & Perspective", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.updatePageQuadAndWarp(pageId, quad)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("crop_done_btn")
                    ) {
                        Text("Apply", fontWeight = FontWeight.Bold, color = EmeraldLight, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        val bmp = rawBitmap
                        if (bmp != null) {
                            val res = DocumentDetector().processFrame(bmp)
                            quad = res.quad
                        }
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EmeraldLight)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Auto Detect", color = EmeraldLight)
                    }

                    TextButton(onClick = {
                        quad = DocumentQuad.fullQuad()
                    }) {
                        Icon(Icons.Default.CropFree, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Full Image")
                    }

                    IconButton(onClick = {
                        viewModel.rotateActivePage()
                        // reload
                        coroutineScope.launch {
                            val p = viewModel.uiState.value.activePages.find { it.id == pageId }
                            if (p != null) {
                                rawBitmap = ImageProcessor.loadBitmapFromFile(p.rawImagePath)
                            }
                        }
                    }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
        ) {
            val bmp = rawBitmap
            if (bmp != null && containerSize.width > 0 && containerSize.height > 0) {
                // Calculate scale and letterboxing
                val canvasW = containerSize.width.toFloat()
                val canvasH = containerSize.height.toFloat()

                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()

                val scale = minOf(canvasW / bmpW, canvasH / bmpH) * 0.92f
                val renderW = bmpW * scale
                val renderH = bmpH * scale
                val offsetX = (canvasW - renderW) / 2f
                val offsetY = (canvasH - renderH) / 2f

                // Touch listener for dragging corners
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(quad, renderW, renderH, offsetX, offsetY) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val touchXNorm = (startOffset.x - offsetX) / renderW
                                    val touchYNorm = (startOffset.y - offsetY) / renderH

                                    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                                    var closestIdx = -1
                                    var minDist = 0.15f // threshold in normalized units

                                    corners.forEachIndexed { i, pt ->
                                        val dist = hypot(pt.x - touchXNorm, pt.y - touchYNorm)
                                        if (dist < minDist) {
                                            minDist = dist
                                            closestIdx = i
                                        }
                                    }
                                    activeDraggingCorner = if (closestIdx != -1) closestIdx else null
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val activeIdx = activeDraggingCorner ?: return@detectDragGestures
                                    val deltaXNorm = dragAmount.x / renderW
                                    val deltaYNorm = dragAmount.y / renderH

                                    quad = when (activeIdx) {
                                        0 -> quad.copy(topLeft = PointF((quad.topLeft.x + deltaXNorm).coerceIn(0f, 0.6f), (quad.topLeft.y + deltaYNorm).coerceIn(0f, 0.6f)))
                                        1 -> quad.copy(topRight = PointF((quad.topRight.x + deltaXNorm).coerceIn(0.4f, 1f), (quad.topRight.y + deltaYNorm).coerceIn(0f, 0.6f)))
                                        2 -> quad.copy(bottomRight = PointF((quad.bottomRight.x + deltaXNorm).coerceIn(0.4f, 1f), (quad.bottomRight.y + deltaYNorm).coerceIn(0.4f, 1f)))
                                        3 -> quad.copy(bottomLeft = PointF((quad.bottomLeft.x + deltaXNorm).coerceIn(0f, 0.6f), (quad.bottomLeft.y + deltaYNorm).coerceIn(0.4f, 1f)))
                                        else -> quad
                                    }
                                },
                                onDragEnd = {
                                    activeDraggingCorner = null
                                },
                                onDragCancel = {
                                    activeDraggingCorner = null
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Draw bitmap image
                        val imageBitmap = bmp.asImageBitmap()
                        drawImage(
                            image = imageBitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(renderW.toInt(), renderH.toInt())
                        )

                        // Convert normalized quad to canvas coordinates
                        val tl = Offset(offsetX + quad.topLeft.x * renderW, offsetY + quad.topLeft.y * renderH)
                        val tr = Offset(offsetX + quad.topRight.x * renderW, offsetY + quad.topRight.y * renderH)
                        val br = Offset(offsetX + quad.bottomRight.x * renderW, offsetY + quad.bottomRight.y * renderH)
                        val bl = Offset(offsetX + quad.bottomLeft.x * renderW, offsetY + quad.bottomLeft.y * renderH)

                        val poly = Path().apply {
                            moveTo(tl.x, tl.y)
                            lineTo(tr.x, tr.y)
                            lineTo(br.x, br.y)
                            lineTo(bl.x, bl.y)
                            close()
                        }

                        // Shaded quad area
                        drawPath(poly, color = EmeraldLight.copy(alpha = 0.15f))
                        drawPath(poly, color = EmeraldLight, style = Stroke(width = 3.5f))

                        // Draw 4 corner interactive control knobs
                        val corners = listOf(tl, tr, br, bl)
                        corners.forEachIndexed { idx, pt ->
                            val isKnobActive = activeDraggingCorner == idx
                            drawCircle(
                                color = if (isKnobActive) CyanScan else EmeraldLight,
                                radius = if (isKnobActive) 28f else 22f,
                                center = pt
                            )
                            drawCircle(
                                color = Color.White,
                                radius = if (isKnobActive) 14f else 10f,
                                center = pt
                            )
                        }
                    }

                    // Floating Corner Magnifier Loupe when dragging
                    if (activeDraggingCorner != null) {
                        val activePt = when (activeDraggingCorner) {
                            0 -> quad.topLeft
                            1 -> quad.topRight
                            2 -> quad.bottomRight
                            else -> quad.bottomLeft
                        }
                        CornerMagnifierLoupe(
                            bitmap = rawBitmap,
                            touchXNormalized = activePt.x,
                            touchYNormalized = activePt.y,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EmeraldLight)
                }
            }
        }
    }
}
