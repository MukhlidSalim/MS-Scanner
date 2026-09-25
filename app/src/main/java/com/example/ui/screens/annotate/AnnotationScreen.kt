package com.example.ui.screens.annotate

import androidx.compose.ui.res.stringResource
import com.example.R
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.ui.viewmodel.DocumentViewModel

enum class AnnotateTool {
    PEN,
    HIGHLIGHTER,
    REDACT,
    SIGNATURE
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
    var penColor by remember { mutableStateOf(Color.Black) }
    val paths = remember { mutableStateListOf<DrawPath>() }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    val redactions = remember { mutableStateListOf<RedactionRect>() }
    var redactStart by remember { mutableStateOf<Offset?>(null) }
    var redactEnd by remember { mutableStateOf<Offset?>(null) }

    var showSignatureDialog by remember { mutableStateOf(false) }
    val placedSignatures = remember { mutableStateListOf<PlacedSignature>() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_sign___annotate), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (paths.isNotEmpty()) paths.removeAt(paths.size - 1)
                            else if (redactions.isNotEmpty()) redactions.removeAt(redactions.size - 1)
                        },
                        enabled = paths.isNotEmpty() || redactions.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.desc_undo))
                    }

                    TextButton(
                        onClick = {
                            viewModel.saveAnnotations(paths, redactions, placedSignatures)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("save_annotation_btn")
                    ) {
                        Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold, color = EmeraldLight, fontSize = 16.sp)
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
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Tool Selection Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pen
                        FilterChip(
                            selected = activeTool == AnnotateTool.PEN,
                            onClick = { activeTool = AnnotateTool.PEN },
                            label = { Text(stringResource(R.string.txt_pen)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        // Highlighter
                        FilterChip(
                            selected = activeTool == AnnotateTool.HIGHLIGHTER,
                            onClick = { activeTool = AnnotateTool.HIGHLIGHTER },
                            label = { Text(stringResource(R.string.txt_highlight)) },
                            leadingIcon = { Icon(Icons.Default.BorderColor, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        // Redaction Blackout
                        FilterChip(
                            selected = activeTool == AnnotateTool.REDACT,
                            onClick = { activeTool = AnnotateTool.REDACT },
                            label = { Text(stringResource(R.string.txt_redact)) },
                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        // Signature
                        FilterChip(
                            selected = activeTool == AnnotateTool.SIGNATURE,
                            onClick = { showSignatureDialog = true },
                            label = { Text(stringResource(R.string.txt_signature)) },
                            leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    // Pen Colors Bar
                    if (activeTool == AnnotateTool.PEN) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            val colors = listOf(Color.Black, Color(0xFF1D4ED8), Color(0xFFDC2626), Color(0xFF16A34A))
                            colors.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(2.dp, if (penColor == c) EmeraldLight else Color.Transparent, CircleShape)
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
                .background(Color(0xFF0B132B))
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
                                    val nx = (startPt.x - offsetX) / renderW
                                    val ny = (startPt.y - offsetY) / renderH
                                    if (activeTool == AnnotateTool.REDACT) {
                                        redactStart = Offset(nx, ny)
                                        redactEnd = Offset(nx, ny)
                                    } else {
                                        currentPoints.clear()
                                        currentPoints.add(StrokePoint(nx, ny))
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = (change.position.x - offsetX) / renderW
                                    val ny = (change.position.y - offsetY) / renderH
                                    if (activeTool == AnnotateTool.REDACT) {
                                        redactEnd = Offset(nx, ny)
                                    } else {
                                        currentPoints.add(StrokePoint(nx, ny))
                                    }
                                },
                                onDragEnd = {
                                    if (activeTool == AnnotateTool.REDACT) {
                                        val s = redactStart
                                        val e = redactEnd
                                        if (s != null && e != null) {
                                            redactions.add(
                                                RedactionRect(
                                                    minOf(s.x, e.x),
                                                    minOf(s.y, e.y),
                                                    maxOf(s.x, e.x),
                                                    maxOf(s.y, e.y)
                                                )
                                            )
                                        }
                                        redactStart = null
                                        redactEnd = null
                                    } else {
                                        if (currentPoints.size > 1) {
                                            val color = if (activeTool == AnnotateTool.HIGHLIGHTER) Color(0xFFFFEB3B).toArgb() else penColor.toArgb()
                                            paths.add(
                                                DrawPath(
                                                    points = currentPoints.toList(),
                                                    color = color,
                                                    strokeWidth = if (activeTool == AnnotateTool.HIGHLIGHTER) 14f else 3.5f,
                                                    isHighlighter = activeTool == AnnotateTool.HIGHLIGHTER
                                                )
                                            )
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

                        // 4. Draw placed signatures
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

                        // 5. Draw Redaction Rectangles (Blackout)
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

                        // Active redaction rect during drag
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
                    CircularProgressIndicator(color = EmeraldLight)
                }
            }
        }
    }

    // Signature Pad Modal Dialog
    if (showSignatureDialog) {
        val signaturePoints = remember { mutableStateListOf<StrokePoint>() }
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text(stringResource(R.string.txt_draw_electronic_signature)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.txt_sign_inside_the_box_below), fontSize = 12.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
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
                            // Generate transparent signature bitmap
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

                            // Place in center of current page
                            placedSignatures.add(
                                PlacedSignature(
                                    signatureBitmap = sigBmp,
                                    x = 0.5f,
                                    y = 0.75f,
                                    scale = 0.35f
                                )
                            )
                            viewModel.saveSignatureToVault("Signature", sigBmp)
                        }
                        showSignatureDialog = false
                    }
                ) {
                    Text(stringResource(R.string.txt_place_signature))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}
