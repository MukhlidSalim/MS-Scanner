package com.example.ui.screens.annotate

import androidx.compose.ui.res.stringResource
import com.example.R
import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.annotation.*
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.StudioCanvasBg
import com.example.ui.viewmodel.DocumentViewModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class AnnotateTool {
    PEN,
    HIGHLIGHTER,
    ARROW,
    RECTANGLE,
    CIRCLE,
    TEXT,
    SIGNATURE,
    REDACT,
    ERASER,
    BRIGHTNESS,
    CONTRAST
}

sealed class AnnotationAction {
    data class PathAction(val path: DrawPath) : AnnotationAction()
    data class ShapeAction(val shape: DrawShape) : AnnotationAction()
    data class RedactionAction(val rect: RedactionRect) : AnnotationAction()
    data class SignatureAction(val sig: PlacedSignature) : AnnotationAction()
    data class TextAction(val text: PlacedText) : AnnotationAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationScreen(
    docId: Long,
    pageId: Long,
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val page = uiState.activePages.find { it.id == pageId }

    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(pageId) {
        if (page != null) {
            baseBitmap = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
        }
    }

    var activeTool by remember { mutableStateOf(AnnotateTool.PEN) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }

    val placedTexts = remember { mutableStateListOf<PlacedText>() }
    var showTextDialog by remember { mutableStateOf(false) }
    var tempTextInput by remember { mutableStateOf("") }
    var penColor by remember { mutableStateOf(Color.Black) }

    val paths = remember { mutableStateListOf<DrawPath>() }
    val shapes = remember { mutableStateListOf<DrawShape>() }
    val redactions = remember { mutableStateListOf<RedactionRect>() }
    val placedSignatures = remember { mutableStateListOf<PlacedSignature>() }

    // Undo & Redo History
    val undoStack = remember { mutableStateListOf<AnnotationAction>() }
    val redoStack = remember { mutableStateListOf<AnnotationAction>() }

    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }
    var redactStart by remember { mutableStateOf<Offset?>(null) }
    var redactEnd by remember { mutableStateOf<Offset?>(null) }

    var showSignatureDialog by remember { mutableStateOf(false) }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val lastAction = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(lastAction)
            when (lastAction) {
                is AnnotationAction.PathAction -> paths.remove(lastAction.path)
                is AnnotationAction.ShapeAction -> shapes.remove(lastAction.shape)
                is AnnotationAction.RedactionAction -> redactions.remove(lastAction.rect)
                is AnnotationAction.SignatureAction -> placedSignatures.remove(lastAction.sig)
                is AnnotationAction.TextAction -> placedTexts.remove(lastAction.text)
            }
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.removeAt(redoStack.size - 1)
            undoStack.add(action)
            when (action) {
                is AnnotationAction.PathAction -> paths.add(action.path)
                is AnnotationAction.ShapeAction -> shapes.add(action.shape)
                is AnnotationAction.RedactionAction -> redactions.add(action.rect)
                is AnnotationAction.SignatureAction -> placedSignatures.add(action.sig)
                is AnnotationAction.TextAction -> placedTexts.add(action.text)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_sign___annotate), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { undo() },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }

                    IconButton(
                        onClick = { redo() },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }

                    Button(
                        onClick = {
                            viewModel.saveAnnotations(
                                paths = paths.toList(),
                                redactions = redactions.toList(),
                                signatures = placedSignatures.toList(),
                                placedTexts = placedTexts.toList(),
                                shapes = shapes.toList(),
                                brightness = brightness,
                                contrast = contrast
                            )
                            onNavigateBack()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp).testTag("save_annotation_btn")
                    ) {
                        Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
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
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sliders for Brightness/Contrast
                    if (activeTool == AnnotateTool.BRIGHTNESS) {
                        Column {
                            Text("Brightness", style = MaterialTheme.typography.labelSmall)
                            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)
                        }
                    } else if (activeTool == AnnotateTool.CONTRAST) {
                        Column {
                            Text("Contrast", style = MaterialTheme.typography.labelSmall)
                            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2.0f)
                        }
                    }

                    // Tool Selection Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = activeTool == AnnotateTool.PEN,
                            onClick = { activeTool = AnnotateTool.PEN },
                            shape = RoundedCornerShape(50),
                            label = { Text(stringResource(R.string.txt_pen)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.HIGHLIGHTER,
                            onClick = { activeTool = AnnotateTool.HIGHLIGHTER },
                            shape = RoundedCornerShape(50),
                            label = { Text(stringResource(R.string.txt_highlight)) },
                            leadingIcon = { Icon(Icons.Default.BorderColor, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.ARROW,
                            onClick = { activeTool = AnnotateTool.ARROW },
                            shape = RoundedCornerShape(50),
                            label = { Text("Arrow") },
                            leadingIcon = { Icon(Icons.Default.ArrowRightAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.RECTANGLE,
                            onClick = { activeTool = AnnotateTool.RECTANGLE },
                            shape = RoundedCornerShape(50),
                            label = { Text("Rectangle") },
                            leadingIcon = { Icon(Icons.Default.CropSquare, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.CIRCLE,
                            onClick = { activeTool = AnnotateTool.CIRCLE },
                            shape = RoundedCornerShape(50),
                            label = { Text("Circle") },
                            leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.TEXT,
                            onClick = { showTextDialog = true },
                            shape = RoundedCornerShape(50),
                            label = { Text("Text") },
                            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.SIGNATURE,
                            onClick = { showSignatureDialog = true },
                            shape = RoundedCornerShape(50),
                            label = { Text(stringResource(R.string.txt_signature)) },
                            leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.REDACT,
                            onClick = { activeTool = AnnotateTool.REDACT },
                            shape = RoundedCornerShape(50),
                            label = { Text(stringResource(R.string.txt_redact)) },
                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.ERASER,
                            onClick = { activeTool = AnnotateTool.ERASER },
                            shape = RoundedCornerShape(50),
                            label = { Text(stringResource(R.string.txt_eraser)) },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    // Pen & Shape Colors Bar
                    if (activeTool in listOf(AnnotateTool.PEN, AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                        ) {
                            val colors = listOf(Color.Black, Color(0xFF1D4ED8), Color(0xFFDC2626), Color(0xFF16A34A), Color(0xFFEAB308))
                            colors.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(2.dp, if (penColor == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                        .clickable { penColor = c }
                                )
                            }
                        }
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
            val bmp = baseBitmap
            if (bmp != null && containerSize.width > 0 && containerSize.height > 0) {
                val canvasW = containerSize.width.toFloat()
                val canvasH = containerSize.height.toFloat()
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()

                val scale = minOf(canvasW / bmpW, canvasH / bmpH) * 0.95f
                val renderW = bmpW * scale
                val renderH = bmpH * scale
                val offsetX = (canvasW - renderW) / 2f
                val offsetY = (canvasH - renderH) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeTool, penColor, renderW, renderH, offsetX, offsetY) {
                            detectDragGestures(
                                onDragStart = { startPt ->
                                    val nx = ((startPt.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((startPt.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    if (activeTool == AnnotateTool.REDACT) {
                                        redactStart = Offset(nx, ny)
                                        redactEnd = Offset(nx, ny)
                                    } else if (activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE)) {
                                        shapeStart = Offset(nx, ny)
                                        shapeEnd = Offset(nx, ny)
                                    } else {
                                        currentPoints.clear()
                                        currentPoints.add(StrokePoint(nx, ny))
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = ((change.position.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((change.position.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    if (activeTool == AnnotateTool.REDACT) {
                                        redactEnd = Offset(nx, ny)
                                    } else if (activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE)) {
                                        shapeEnd = Offset(nx, ny)
                                    } else {
                                        currentPoints.add(StrokePoint(nx, ny))
                                    }
                                },
                                onDragEnd = {
                                    if (activeTool == AnnotateTool.REDACT) {
                                        val s = redactStart
                                        val e = redactEnd
                                        if (s != null && e != null) {
                                            val r = RedactionRect(
                                                minOf(s.x, e.x),
                                                minOf(s.y, e.y),
                                                maxOf(s.x, e.x),
                                                maxOf(s.y, e.y)
                                            )
                                            redactions.add(r)
                                            undoStack.add(AnnotationAction.RedactionAction(r))
                                            redoStack.clear()
                                        }
                                        redactStart = null
                                        redactEnd = null
                                    } else if (activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE)) {
                                        val s = shapeStart
                                        val e = shapeEnd
                                        if (s != null && e != null) {
                                            val type = when (activeTool) {
                                                AnnotateTool.ARROW -> ShapeType.ARROW
                                                AnnotateTool.RECTANGLE -> ShapeType.RECTANGLE
                                                else -> ShapeType.CIRCLE
                                            }
                                            val shape = DrawShape(type, s.x, s.y, e.x, e.y, penColor.toArgb(), 4f)
                                            shapes.add(shape)
                                            undoStack.add(AnnotationAction.ShapeAction(shape))
                                            redoStack.clear()
                                        }
                                        shapeStart = null
                                        shapeEnd = null
                                    } else {
                                        if (currentPoints.size > 1) {
                                            val color = if (activeTool == AnnotateTool.HIGHLIGHTER) Color(0xFFFFEB3B).toArgb() else if (activeTool == AnnotateTool.ERASER) Color.White.toArgb() else penColor.toArgb()
                                            val dp = DrawPath(
                                                points = currentPoints.toList(),
                                                color = color,
                                                strokeWidth = if (activeTool == AnnotateTool.HIGHLIGHTER) 14f else if (activeTool == AnnotateTool.ERASER) 20f else 3.5f,
                                                isHighlighter = activeTool == AnnotateTool.HIGHLIGHTER,
                                                isEraser = activeTool == AnnotateTool.ERASER
                                            )
                                            paths.add(dp)
                                            undoStack.add(AnnotationAction.PathAction(dp))
                                            redoStack.clear()
                                        }
                                        currentPoints.clear()
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // 1. Draw base bitmap
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(renderW.toInt(), renderH.toInt())
                        )

                        // 2. Draw existing paths
                        for (dp in paths) {
                            if (dp.points.size < 2) continue
                            val path = Path().apply {
                                moveTo(offsetX + dp.points[0].x * renderW, offsetY + dp.points[0].y * renderH)
                                for (i in 1 until dp.points.size) {
                                    lineTo(offsetX + dp.points[i].x * renderW, offsetY + dp.points[i].y * renderH)
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color(dp.color).copy(alpha = if (dp.isHighlighter) 0.45f else 1.0f),
                                style = Stroke(width = dp.strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }

                        // 3. Draw active drawing path
                        if (currentPoints.size > 1) {
                            val activeP = Path().apply {
                                moveTo(offsetX + currentPoints[0].x * renderW, offsetY + currentPoints[0].y * renderH)
                                for (i in 1 until currentPoints.size) {
                                    lineTo(offsetX + currentPoints[i].x * renderW, offsetY + currentPoints[i].y * renderH)
                                }
                            }
                            drawPath(
                                path = activeP,
                                color = if (activeTool == AnnotateTool.HIGHLIGHTER) Color(0xFFFFEB3B).copy(alpha = 0.45f) else penColor,
                                style = Stroke(width = if (activeTool == AnnotateTool.HIGHLIGHTER) 14f else 3.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }

                        // 4. Draw existing shapes
                        for (s in shapes) {
                            val sx = offsetX + s.startX * renderW
                            val sy = offsetY + s.startY * renderH
                            val ex = offsetX + s.endX * renderW
                            val ey = offsetY + s.endY * renderH
                            when (s.type) {
                                ShapeType.RECTANGLE -> {
                                    drawRect(
                                        color = Color(s.color),
                                        topLeft = Offset(minOf(sx, ex), minOf(sy, ey)),
                                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy)),
                                        style = Stroke(width = s.strokeWidth)
                                    )
                                }
                                ShapeType.CIRCLE -> {
                                    val cx = (sx + ex) / 2f
                                    val cy = (sy + ey) / 2f
                                    val rx = kotlin.math.abs(ex - sx) / 2f
                                    val ry = kotlin.math.abs(ey - sy) / 2f
                                    drawOval(
                                        color = Color(s.color),
                                        topLeft = Offset(cx - rx, cy - ry),
                                        size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                                        style = Stroke(width = s.strokeWidth)
                                    )
                                }
                                ShapeType.ARROW -> {
                                    drawLine(
                                        color = Color(s.color),
                                        start = Offset(sx, sy),
                                        end = Offset(ex, ey),
                                        strokeWidth = s.strokeWidth
                                    )
                                }
                            }
                        }

                        // 4.5 Draw live shape during drag
                        val ss = shapeStart
                        val se = shapeEnd
                        if (ss != null && se != null) {
                            val sx = offsetX + ss.x * renderW
                            val sy = offsetY + ss.y * renderH
                            val ex = offsetX + se.x * renderW
                            val ey = offsetY + se.y * renderH
                            when (activeTool) {
                                AnnotateTool.RECTANGLE -> {
                                    drawRect(
                                        color = penColor,
                                        topLeft = Offset(minOf(sx, ex), minOf(sy, ey)),
                                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy)),
                                        style = Stroke(width = 4f)
                                    )
                                }
                                AnnotateTool.CIRCLE -> {
                                    val cx = (sx + ex) / 2f
                                    val cy = (sy + ey) / 2f
                                    val rx = kotlin.math.abs(ex - sx) / 2f
                                    val ry = kotlin.math.abs(ey - sy) / 2f
                                    drawOval(
                                        color = penColor,
                                        topLeft = Offset(cx - rx, cy - ry),
                                        size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                                        style = Stroke(width = 4f)
                                    )
                                }
                                AnnotateTool.ARROW -> {
                                    drawLine(
                                        color = penColor,
                                        start = Offset(sx, sy),
                                        end = Offset(ex, ey),
                                        strokeWidth = 4f
                                    )
                                }
                                else -> {}
                            }
                        }

                        // 5. Draw placed signatures
                        for (sig in placedSignatures) {
                            val sigW = (renderW * sig.scale).toInt()
                            val sigH = (sigW * (sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.toFloat())).toInt()
                            val cx = offsetX + sig.x * renderW
                            val cy = offsetY + sig.y * renderH
                            drawImage(
                                image = sig.signatureBitmap.asImageBitmap(),
                                dstOffset = androidx.compose.ui.unit.IntOffset((cx - sigW / 2).toInt(), (cy - sigH / 2).toInt()),
                                dstSize = IntSize(sigW, sigH)
                            )
                        }

                        // 5.5 Draw Placed Texts
                        for (pt in placedTexts) {
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    color = pt.color
                                    textSize = pt.textSize * (renderW / 1080f)
                                }
                                drawText(pt.text, offsetX + pt.x * renderW, offsetY + pt.y * renderH, paint)
                            }
                        }

                        // 6. Draw Redaction Rectangles
                        for (r in redactions) {
                            val rx = offsetX + r.left * renderW
                            val ry = offsetY + r.top * renderH
                            val rw = (r.right - r.left) * renderW
                            val rh = (r.bottom - r.top) * renderH
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(rx, ry),
                                size = androidx.compose.ui.geometry.Size(rw, rh)
                            )
                        }

                        // Live redaction rect during drag
                        val rs = redactStart
                        val re = redactEnd
                        if (rs != null && re != null) {
                            val rx = offsetX + minOf(rs.x, re.x) * renderW
                            val ry = offsetY + minOf(rs.y, re.y) * renderH
                            val rw = kotlin.math.abs(re.x - rs.x) * renderW
                            val rh = kotlin.math.abs(re.y - rs.y) * renderH
                            drawRect(
                                color = Color.Black.copy(alpha = 0.7f),
                                topLeft = Offset(rx, ry),
                                size = androidx.compose.ui.geometry.Size(rw, rh)
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text("Add Text", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempTextInput,
                    onValueChange = { tempTextInput = it },
                    label = { Text("Enter text") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempTextInput.isNotBlank()) {
                            val pt = PlacedText(text = tempTextInput, x = 0.5f, y = 0.5f, color = penColor.toArgb())
                            placedTexts.add(pt)
                            undoStack.add(AnnotationAction.TextAction(pt))
                            redoStack.clear()
                            tempTextInput = ""
                        }
                        showTextDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Add", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    if (showSignatureDialog) {
        val signaturePoints = remember { mutableStateListOf<StrokePoint>() }
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.txt_draw_electronic_signature), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.txt_sign_inside_the_box_below), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { signaturePoints.add(StrokePoint(it.x, it.y)) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        signaturePoints.add(StrokePoint(change.position.x, change.position.y))
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            if (signaturePoints.size > 1) {
                                val p = Path().apply {
                                    moveTo(signaturePoints[0].x, signaturePoints[0].y)
                                    for (i in 1 until signaturePoints.size) {
                                        lineTo(signaturePoints[i].x, signaturePoints[i].y)
                                    }
                                }
                                drawPath(p, Color.Black, style = Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                            }
                        }
                    }

                    TextButton(
                        onClick = { signaturePoints.clear() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.txt_clear_signature))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (signaturePoints.size > 1) {
                            val sigBmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(sigBmp)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.BLACK
                                strokeWidth = 5f
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                            }
                            val p = android.graphics.Path().apply {
                                moveTo(signaturePoints[0].x, signaturePoints[0].y)
                                for (i in 1 until signaturePoints.size) {
                                    lineTo(signaturePoints[i].x, signaturePoints[i].y)
                                }
                            }
                            canvas.drawPath(p, paint)

                            val ps = PlacedSignature(
                                signatureBitmap = sigBmp,
                                x = 0.5f,
                                y = 0.75f,
                                scale = 0.35f
                            )
                            placedSignatures.add(ps)
                            undoStack.add(AnnotationAction.SignatureAction(ps))
                            redoStack.clear()
                            viewModel.saveSignatureToVault("Signature", sigBmp)
                        }
                        showSignatureDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.txt_place_signature), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}
