package com.example.ui.screens.annotate
import androidx.compose.ui.res.stringResource
import com.example.R
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.engine.annotation.*
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.GoldBase
import com.example.ui.theme.StudioCanvasBg
import com.example.ui.viewmodel.EditSessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
    viewModel: EditSessionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val page = uiState.activePages.find { it.id == pageId }
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
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

    // ---------------------------------------------------------------------------------------------
    // Reused-signature editing: a placed signature is selectable (tap on it), draggable (move),
    // resizable (slider bound to `scale`), and re-colourable (swatches, applied via
    // SignatureStore.tinted()). `.copy(...)` is used to update an entry in place since PlacedSignature
    // is a data class used here with named arguments (signatureBitmap, x, y, scale).
    // ---------------------------------------------------------------------------------------------
    var selectedSignatureIndex by remember { mutableStateOf<Int?>(null) }
    /** Approximate on-screen half-size of a placed signature, for hit-testing and the move gesture. */
    fun placedSignatureHalfSize(sig: PlacedSignature, renderW: Float): Pair<Float, Float> {
        val w = renderW * sig.scale
        val h = w * (sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.toFloat().coerceAtLeast(1f))
        return (w / 2f) to (h / 2f)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.ShowToast -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val lastAction = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(lastAction)
            when (lastAction) {
                is AnnotationAction.PathAction -> paths.remove(lastAction.path)
                is AnnotationAction.ShapeAction -> shapes.remove(lastAction.shape)
                is AnnotationAction.RedactionAction -> redactions.remove(lastAction.rect)
                is AnnotationAction.SignatureAction -> {
                    placedSignatures.remove(lastAction.sig)
                    selectedSignatureIndex = null
                }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                // A selected placed signature gets its OWN control bar (Move is done by dragging it on
                // the canvas directly; this bar handles Resize, Recolor, Duplicate and Delete/Done).
                val selIdx = selectedSignatureIndex
                if (selIdx != null && selIdx in placedSignatures.indices) {
                    val selected = placedSignatures[selIdx]
                    val isArabicLocal = context.resources.configuration.locales[0].language == "ar"
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isArabicLocal) "تعديل التوقيع" else "Edit signature",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { selectedSignatureIndex = null }) {
                                Text(if (isArabicLocal) "تم" else "Done")
                            }
                        }
                        // Resize
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhotoSizeSelectSmall, contentDescription = null, modifier = Modifier.size(18.dp))
                            Slider(
                                value = selected.scale,
                                onValueChange = { newScale ->
                                    placedSignatures[selIdx] = selected.copy(scale = newScale.coerceIn(0.12f, 0.7f))
                                },
                                valueRange = 0.12f..0.7f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        // Recolor — reuses SignatureStore.tinted()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SignatureStore.DEFAULT_COLORS.forEach { colorInt ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        .clickable {
                                            val recolored = SignatureStore.tinted(selected.signatureBitmap, colorInt)
                                            placedSignatures[selIdx] = selected.copy(signatureBitmap = recolored)
                                        }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                placedSignatures.removeAt(selIdx)
                                selectedSignatureIndex = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            if (isArabicLocal) "اسحب التوقيع على الصفحة لتحريكه"
                            else "Drag the signature on the page to move it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Surface
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            onClick = { activeTool = AnnotateTool.SIGNATURE; showSignatureDialog = true },
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
                        // Tap layer: selects / deselects a placed signature when the SIGNATURE tool is
                        // active. Kept separate from the drawing drag layer so pen/shape/redact tools are
                        // completely unaffected.
                        .pointerInput(activeTool, placedSignatures.size, renderW, renderH, offsetX, offsetY) {
                            if (activeTool != AnnotateTool.SIGNATURE) return@pointerInput
                            detectTapGestures { tapPos ->
                                var hit: Int? = null
                                for (i in placedSignatures.indices.reversed()) {
                                    val sig = placedSignatures[i]
                                    val (halfW, halfH) = placedSignatureHalfSize(sig, renderW)
                                    val cx = offsetX + sig.x * renderW
                                    val cy = offsetY + sig.y * renderH
                                    if (kotlin.math.abs(tapPos.x - cx) <= halfW && kotlin.math.abs(tapPos.y - cy) <= halfH) {
                                        hit = i
                                        break
                                    }
                                }
                                selectedSignatureIndex = hit
                            }
                        }
                        // Move layer: drags the currently selected signature. Separate pointerInput keyed
                        // on the selected index so it rebinds cleanly when the selection changes.
                        .pointerInput(activeTool, selectedSignatureIndex, renderW, renderH, offsetX, offsetY) {
                            val idx = selectedSignatureIndex
                            if (activeTool != AnnotateTool.SIGNATURE || idx == null || idx !in placedSignatures.indices) return@pointerInput
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val current = placedSignatures.getOrNull(idx) ?: return@detectDragGestures
                                val newX = (current.x + dragAmount.x / renderW).coerceIn(0.05f, 0.95f)
                                val newY = (current.y + dragAmount.y / renderH).coerceIn(0.05f, 0.95f)
                                placedSignatures[idx] = current.copy(x = newX, y = newY)
                            }
                        }
                        .pointerInput(activeTool, penColor, renderW, renderH, offsetX, offsetY) {
                            if (activeTool == AnnotateTool.SIGNATURE) return@pointerInput
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
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(renderW.toInt(), renderH.toInt())
                        )
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
                        // Placed signatures: the selected one gets a selection outline so the user can
                        // see what Resize/Recolor/Drag currently applies to.
                        for ((i, sig) in placedSignatures.withIndex()) {
                            val sigW = (renderW * sig.scale).toInt()
                            val sigH = (sigW * (sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.toFloat())).toInt()
                            val cx = offsetX + sig.x * renderW
                            val cy = offsetY + sig.y * renderH
                            drawImage(
                                image = sig.signatureBitmap.asImageBitmap(),
                                dstOffset = androidx.compose.ui.unit.IntOffset((cx - sigW / 2).toInt(), (cy - sigH / 2).toInt()),
                                dstSize = IntSize(sigW, sigH)
                            )
                            if (i == selectedSignatureIndex) {
                                drawRect(
                                    color = GoldBase,
                                    topLeft = Offset(cx - sigW / 2f - 6f, cy - sigH / 2f - 6f),
                                    size = androidx.compose.ui.geometry.Size(sigW + 12f, sigH + 12f),
                                    style = Stroke(width = 2.5f)
                                )
                            }
                        }
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
        val isArabic = context.resources.configuration.locales[0].language == "ar"

        /** Places a signature bitmap (new or from the vault) and immediately selects it for editing,
         *  so Change Color / Resize / Move are available right away. */
        fun placeAndSelect(bmp: Bitmap) {
            val ps = PlacedSignature(signatureBitmap = bmp, x = 0.5f, y = 0.75f, scale = 0.35f)
            placedSignatures.add(ps)
            undoStack.add(AnnotationAction.SignatureAction(ps))
            redoStack.clear()
            selectedSignatureIndex = placedSignatures.lastIndex
            activeTool = AnnotateTool.SIGNATURE
            showSignatureDialog = false
        }

        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.txt_draw_electronic_signature), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (uiState.savedSignatures.isNotEmpty()) {
                        Text(
                            if (isArabic) "أو اختر توقيعًا محفوظًا" else "Or pick a saved signature",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(uiState.savedSignatures, key = { it.id }) { sig ->
                                Box(
                                    modifier = Modifier
                                        .size(76.dp, 46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .clickable {
                                            coroutineScope.launch {
                                                val bmp = withContext(Dispatchers.IO) {
                                                    SignatureStore.loadDisplayable(sig.imagePath, 800)
                                                }
                                                if (bmp != null) placeAndSelect(bmp)
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = File(sig.imagePath),
                                        contentDescription = sig.title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(4.dp)
                                    )
                                    IconButton(
                                        onClick = { viewModel.deleteSignatureFromVault(sig.id) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = if (isArabic) "حذف" else "Delete",
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
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
                    Text(
                        if (isArabic) "لرسم توقيع في شاشة كاملة ومريحة، استخدم «توقيعاتي» في الإعدادات."
                        else "For a full-screen, comfortable drawing area, use \"My Signatures\" in Settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            viewModel.saveSignatureToVault("Signature", sigBmp)
                            placeAndSelect(sigBmp)
                        } else {
                            showSignatureDialog = false
                        }
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
