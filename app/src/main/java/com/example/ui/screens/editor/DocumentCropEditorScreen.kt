package com.example.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.CyanScan
import com.example.ui.theme.Emerald400
import com.example.ui.theme.StudioCanvasBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class EditorTab {
    CROP,
    FILTERS,
    ADJUST
}

data class EditorState(
    val quad: DocumentQuad,
    val rotation: Int,
    val filter: FilterType,
    val brightness: Float,
    val contrast: Float,
    val sharpen: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentCropEditorScreen(
    imagePath: String,
    onCropped: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var selectedTab by remember { mutableStateOf(EditorTab.CROP) }

    // Core state
    var quad by remember { mutableStateOf(DocumentQuad.defaultQuad()) }
    var rotationDegrees by remember { mutableStateOf(0) }
    var selectedFilter by remember { mutableStateOf(FilterType.AUTO) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    var isSharpenEnabled by remember { mutableStateOf(false) }

    // History stack for Undo / Redo
    val undoStack = remember { mutableStateListOf<EditorState>() }
    val redoStack = remember { mutableStateListOf<EditorState>() }

    fun captureState(): EditorState {
        return EditorState(
            quad = quad,
            rotation = rotationDegrees,
            filter = selectedFilter,
            brightness = brightness,
            contrast = contrast,
            sharpen = isSharpenEnabled
        )
    }

    fun pushHistory() {
        undoStack.add(captureState())
        redoStack.clear()
    }

    // Touch handle tracking
    var activeHandleIndex by remember { mutableStateOf<Int?>(null) }
    var activeTouchOffset by remember { mutableStateOf<Offset?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showBoundary by remember { mutableStateOf(true) }

    LaunchedEffect(imagePath) {
        withContext(Dispatchers.IO) {
            val bmp = ImageProcessor.loadBitmapFromFile(imagePath, 2048)
            originalBitmap = bmp
            currentBitmap = bmp
            if (bmp != null) {
                quad = ImageProcessor.detectDocumentQuad(bmp)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Document Editor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = when (selectedTab) {
                                EditorTab.CROP -> "Perspective & Boundaries"
                                EditorTab.FILTERS -> "Color & Scan Filters"
                                EditorTab.ADJUST -> "Brightness & Clarity"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val current = captureState()
                                redoStack.add(current)
                                val previous = undoStack.removeAt(undoStack.lastIndex)
                                quad = previous.quad
                                rotationDegrees = previous.rotation
                                selectedFilter = previous.filter
                                brightness = previous.brightness
                                contrast = previous.contrast
                                isSharpenEnabled = previous.sharpen
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }

                    // Redo
                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val current = captureState()
                                undoStack.add(current)
                                val next = redoStack.removeAt(redoStack.lastIndex)
                                quad = next.quad
                                rotationDegrees = next.rotation
                                selectedFilter = next.filter
                                brightness = next.brightness
                                contrast = next.contrast
                                isSharpenEnabled = next.sharpen
                            }
                        },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }

                    // Reset to original
                    IconButton(onClick = {
                        pushHistory()
                        originalBitmap?.let { bmp ->
                            quad = ImageProcessor.detectDocumentQuad(bmp)
                            rotationDegrees = 0
                            selectedFilter = FilterType.AUTO
                            brightness = 0f
                            contrast = 1f
                            isSharpenEnabled = false
                        }
                    }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset")
                    }

                    // Save Button
                    Button(
                        onClick = {
                            val bmp = originalBitmap ?: return@Button
                            isProcessing = true
                            coroutineScope.launch {
                                val rotated = if (rotationDegrees != 0) ImageProcessor.rotateBitmap(bmp, rotationDegrees) else bmp
                                val warped = ImageProcessor.warpPerspective(rotated, quad)
                                val filtered = ImageProcessor.applyFilter(warped, selectedFilter)
                                val enhanced = if (brightness != 0f || contrast != 1f || isSharpenEnabled) {
                                    val adj = ImageProcessor.adjustEnhancements(filtered, brightness, contrast, isSharpenEnabled)
                                    filtered.recycle()
                                    adj
                                } else {
                                    filtered
                                }

                                val newPath = ImageProcessor.saveBitmapToFile(context, enhanced, "edit_proc_")

                                if (rotated != bmp && rotated != warped) rotated.recycle()
                                if (warped != bmp) warped.recycle()
                                enhanced.recycle()
                                isProcessing = false
                                onCropped(newPath)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column {
                    // Secondary Tab Content Panels
                    when (selectedTab) {
                        EditorTab.CROP -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Auto Detect
                                TextButton(onClick = {
                                    originalBitmap?.let { bmp ->
                                        pushHistory()
                                        quad = ImageProcessor.detectDocumentQuad(bmp)
                                    }
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Emerald400)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Auto Detect", color = Emerald400, fontWeight = FontWeight.SemiBold)
                                }

                                // Full Document
                                TextButton(onClick = {
                                    pushHistory()
                                    quad = DocumentQuad.fullQuad()
                                }) {
                                    Icon(Icons.Default.CropFree, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Full Document", fontWeight = FontWeight.SemiBold)
                                }

                                // Rotate 90 CW
                                IconButton(onClick = {
                                    pushHistory()
                                    rotationDegrees = (rotationDegrees + 90) % 360
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90°")
                                }
                                
                                // Boundary Toggle
                                IconButton(onClick = { showBoundary = !showBoundary }) {
                                    Icon(
                                        imageVector = if (showBoundary) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Boundary"
                                    )
                                }
                            }
                        }

                        EditorTab.FILTERS -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterType.values().forEach { flt ->
                                    val isSelected = selectedFilter == flt
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            pushHistory()
                                            selectedFilter = flt
                                        },
                                        label = { Text(flt.displayNameEn, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }

                        EditorTab.ADJUST -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Brightness Slider
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.Brightness6, contentDescription = "Brightness", modifier = Modifier.size(20.dp))
                                    Text("Brightness", fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                    Slider(
                                        value = brightness,
                                        onValueChange = { brightness = it },
                                        onValueChangeFinished = { pushHistory() },
                                        valueRange = -40f..40f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${brightness.toInt()}", fontSize = 11.sp, modifier = Modifier.width(28.dp))
                                }

                                // Contrast Slider
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.Contrast, contentDescription = "Contrast", modifier = Modifier.size(20.dp))
                                    Text("Contrast", fontSize = 12.sp, modifier = Modifier.width(70.dp))
                                    Slider(
                                        value = contrast,
                                        onValueChange = { contrast = it },
                                        onValueChangeFinished = { pushHistory() },
                                        valueRange = 0.6f..2.0f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(String.format("%.1f", contrast), fontSize = 11.sp, modifier = Modifier.width(28.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Primary Navigation Tabs
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.height(56.dp)
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.CROP,
                            onClick = { selectedTab = EditorTab.CROP },
                            icon = { Icon(Icons.Default.Crop, contentDescription = "Crop") },
                            label = { Text("Crop", fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.FILTERS,
                            onClick = { selectedTab = EditorTab.FILTERS },
                            icon = { Icon(Icons.Default.ColorLens, contentDescription = "Filters") },
                            label = { Text("Filters", fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.ADJUST,
                            onClick = { selectedTab = EditorTab.ADJUST },
                            icon = { Icon(Icons.Default.Tune, contentDescription = "Adjust") },
                            label = { Text("Adjust", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(StudioCanvasBg)
                .onSizeChanged { containerSize = it }
        ) {
            val bmp = originalBitmap
            if (bmp != null && containerSize.width > 0 && containerSize.height > 0) {
                val canvasW = containerSize.width.toFloat()
                val canvasH = containerSize.height.toFloat()
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()

                val scale = minOf(canvasW / bmpW, canvasH / bmpH) * 0.90f
                val renderW = bmpW * scale
                val renderH = bmpH * scale
                val offsetX = (canvasW - renderW) / 2f
                val offsetY = (canvasH - renderH) / 2f

                val handles = listOf(
                    quad.topLeft,
                    quad.topRight,
                    quad.bottomRight,
                    quad.bottomLeft
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(renderW, renderH, offsetX, offsetY, quad) {
                            detectDragGestures(
                                onDragStart = { startPt ->
                                    val touchX = (startPt.x - offsetX) / renderW
                                    val touchY = (startPt.y - offsetY) / renderH

                                    var closestIdx = -1
                                    var closestDist = Float.MAX_VALUE

                                    for (i in handles.indices) {
                                        val dist = hypot(handles[i].x - touchX, handles[i].y - touchY)
                                        if (dist < closestDist) {
                                            closestDist = dist
                                            closestIdx = i
                                        }
                                    }

                                    if (closestDist < 0.22f) {
                                        activeHandleIndex = closestIdx
                                        activeTouchOffset = startPt
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val idx = activeHandleIndex ?: return@detectDragGestures
                                    activeTouchOffset = change.position

                                    val newX = ((change.position.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val newY = ((change.position.y - offsetY) / renderH).coerceIn(0f, 1f)

                                    quad = when (idx) {
                                        0 -> quad.copy(topLeft = PointF(newX, newY))
                                        1 -> quad.copy(topRight = PointF(newX, newY))
                                        2 -> quad.copy(bottomRight = PointF(newX, newY))
                                        3 -> quad.copy(bottomLeft = PointF(newX, newY))
                                        else -> quad
                                    }
                                },
                                onDragEnd = {
                                    pushHistory()
                                    activeHandleIndex = null
                                    activeTouchOffset = null
                                },
                                onDragCancel = {
                                    activeHandleIndex = null
                                    activeTouchOffset = null
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // 1. Draw image
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(renderW.toInt(), renderH.toInt())
                        )

                        // 2. Polygon path
                        val p = Path().apply {
                            moveTo(offsetX + quad.topLeft.x * renderW, offsetY + quad.topLeft.y * renderH)
                            lineTo(offsetX + quad.topRight.x * renderW, offsetY + quad.topRight.y * renderH)
                            lineTo(offsetX + quad.bottomRight.x * renderW, offsetY + quad.bottomRight.y * renderH)
                            lineTo(offsetX + quad.bottomLeft.x * renderW, offsetY + quad.bottomLeft.y * renderH)
                            close()
                        }

                        // Draw Corner Handles with touch circles
                        val pts = listOf(
                            Offset(offsetX + quad.topLeft.x * renderW, offsetY + quad.topLeft.y * renderH),
                            Offset(offsetX + quad.topRight.x * renderW, offsetY + quad.topRight.y * renderH),
                            Offset(offsetX + quad.bottomRight.x * renderW, offsetY + quad.bottomRight.y * renderH),
                            Offset(offsetX + quad.bottomLeft.x * renderW, offsetY + quad.bottomLeft.y * renderH)
                        )

                        if (showBoundary) {
                            // Shaded area
                            drawPath(p, color = Emerald400.copy(alpha = 0.15f))
                            drawPath(p, color = Emerald400, style = Stroke(width = 3.dp.toPx()))

                            for ((i, pt) in pts.withIndex()) {
                                val isActive = activeHandleIndex == i
                                drawCircle(
                                    color = Color.White,
                                    radius = if (isActive) 15.dp.toPx() else 11.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = Emerald400,
                                    radius = if (isActive) 11.dp.toPx() else 8.dp.toPx(),
                                    center = pt
                                )
                            }
                        }

                        // Draw Midpoint Handles (for edge dragging guidance)
                        val midpoints = listOf(
                            (pts[0] + pts[1]) / 2f,
                            (pts[1] + pts[2]) / 2f,
                            (pts[2] + pts[3]) / 2f,
                            (pts[3] + pts[0]) / 2f
                        )
                        for (mp in midpoints) {
                            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 5.dp.toPx(), center = mp)
                            drawCircle(color = Emerald400, radius = 3.5.dp.toPx(), center = mp)
                        }
                    }

                    // Magnifying Loupe Overlay when dragging a corner handle
                    activeHandleIndex?.let { idx ->
                        val currentTouch = activeTouchOffset
                        if (currentTouch != null) {
                            val loupeSize = 110.dp
                            val loupeRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { (loupeSize / 2).toPx() }
                            val activePt = when (idx) {
                                0 -> quad.topLeft
                                1 -> quad.topRight
                                2 -> quad.bottomRight
                                else -> quad.bottomLeft
                            }

                            // Center loupe 80dp above finger
                            val loupeCenter = Offset(
                                x = currentTouch.x.coerceIn(loupeRadiusPx + 16f, canvasW - loupeRadiusPx - 16f),
                                y = (currentTouch.y - 120.dp.value * 2.5f).coerceAtLeast(loupeRadiusPx + 20f)
                            )

                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (loupeCenter.x - loupeRadiusPx).toInt(),
                                            (loupeCenter.y - loupeRadiusPx).toInt()
                                        )
                                    }
                                    .size(loupeSize)
                                    .shadow(12.dp, CircleShape)
                                    .border(3.dp, Emerald400, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val zoomFactor = 2.4f
                                    val focusBmpX = activePt.x * bmp.width
                                    val focusBmpY = activePt.y * bmp.height

                                    val srcRectW = (bmp.width / zoomFactor).coerceAtLeast(50f)
                                    val srcRectH = (bmp.height / zoomFactor).coerceAtLeast(50f)

                                    val srcLeft = (focusBmpX - srcRectW / 2).coerceIn(0f, bmp.width - srcRectW)
                                    val srcTop = (focusBmpY - srcRectH / 2).coerceIn(0f, bmp.height - srcRectH)

                                    drawImage(
                                        image = bmp.asImageBitmap(),
                                        srcOffset = IntOffset(srcLeft.toInt(), srcTop.toInt()),
                                        srcSize = IntSize(srcRectW.toInt(), srcRectH.toInt()),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                    )

                                    // Crosshair in center of loupe
                                    val ch = size.width / 2f
                                    val crosshairCol = Emerald400
                                    val crosshairStroke = 1.8.dp.toPx()
                                    drawLine(crosshairCol, Offset(ch - 16f, ch), Offset(ch + 16f, ch), crosshairStroke)
                                    drawLine(crosshairCol, Offset(ch, ch - 16f), Offset(ch, ch + 16f), crosshairStroke)
                                    drawCircle(color = crosshairCol, radius = 3.dp.toPx(), center = Offset(ch, ch))
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Emerald400)
                }
            }
        }
    }
}
