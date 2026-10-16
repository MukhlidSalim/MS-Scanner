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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
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
    /** Objects removed by the eraser in one stroke (undo puts them back). */
    data class EraseAction(val removed: List<AnnotationAction>) : AnnotationAction()
}

/**
 * Eraser behaviour.
 *  OBJECTS: removes the added items it touches (strokes, shapes, texts, signatures, black-out boxes); the page
 *           underneath is shown again exactly as it was — nothing is painted.
 *  COVER:   hides content of the PAGE itself with the paper colour sampled around the touch point (correction
 *           tape), so the result blends with the surrounding paper instead of leaving a white patch.
 */
enum class EraseMode { OBJECTS, COVER }
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
    // Keyed on the page IMAGE, not only the id: when this screen is opened right after a document is
    // loaded (e.g. "Sign" from the PDF reader), activePages is still empty on the first composition and the
    // previous LaunchedEffect(pageId) never ran again -> endless spinner.
    val pageImagePath = page?.processedImagePath
    LaunchedEffect(pageId, pageImagePath) {
        if (pageImagePath != null && baseBitmap == null) {
            baseBitmap = ImageProcessor.loadBitmapFromFile(pageImagePath)
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
    var selectedTextIndex by remember { mutableStateOf<Int?>(null) }
    // Eraser: mode, live finger position (drives the magnifier) and the removals of the current stroke.
    var eraseMode by remember { mutableStateOf(EraseMode.OBJECTS) }
    var eraserFinger by remember { mutableStateOf<Offset?>(null) }
    var coverColor by remember { mutableStateOf(android.graphics.Color.WHITE) }
    val strokeRemovals = remember { mutableStateListOf<AnnotationAction>() }
    var showFullSignaturePad by remember { mutableStateOf(false) }
    /** Approximate on-screen half-size of a placed signature, for hit-testing and the move gesture. */
    fun placedSignatureHalfSize(sig: PlacedSignature, renderW: Float): Pair<Float, Float> {
        val w = renderW * sig.scale
        val h = w * (sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.toFloat().coerceAtLeast(1f))
        return (w / 2f) to (h / 2f)
    }
    fun placedTextHalfSize(pt: PlacedText, renderW: Float): Pair<Float, Float> {
        val fontPx = pt.textSize * (renderW / 1080f)
        val halfW = (pt.text.length.coerceAtLeast(2) * fontPx * 0.35f).coerceAtLeast(36f)
        val halfH = (fontPx * 0.8f).coerceAtLeast(24f)
        return halfW to halfH
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

    fun applyAction(action: AnnotationAction) {
        when (action) {
            is AnnotationAction.PathAction -> paths.add(action.path)
            is AnnotationAction.ShapeAction -> shapes.add(action.shape)
            is AnnotationAction.RedactionAction -> redactions.add(action.rect)
            is AnnotationAction.SignatureAction -> placedSignatures.add(action.sig)
            is AnnotationAction.TextAction -> placedTexts.add(action.text)
            is AnnotationAction.EraseAction -> action.removed.forEach { revertAction(it) }
        }
    }

    fun revertAction(action: AnnotationAction) {
        when (action) {
            is AnnotationAction.PathAction -> paths.remove(action.path)
            is AnnotationAction.ShapeAction -> shapes.remove(action.shape)
            is AnnotationAction.RedactionAction -> redactions.remove(action.rect)
            is AnnotationAction.SignatureAction -> { placedSignatures.remove(action.sig); selectedSignatureIndex = null }
            is AnnotationAction.TextAction -> { placedTexts.remove(action.text); selectedTextIndex = null }
            is AnnotationAction.EraseAction -> action.removed.forEach { applyAction(it) }
        }
    }

    fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        redoStack.add(last)
        revertAction(last)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(next)
        applyAction(next)
    }

    /**
     * Placed signatures / texts are immutable values edited with copy(): the undo history must follow the
     * edited value, otherwise "undo" could no longer find (and remove) a signature after it was resized,
     * recoloured, rotated or moved.
     */
    fun updatePlacedSignature(index: Int, updated: PlacedSignature) {
        val old = placedSignatures.getOrNull(index) ?: return
        placedSignatures[index] = updated
        fun swap(list: MutableList<AnnotationAction>) {
            for (i in list.indices) {
                val a = list[i]
                if (a is AnnotationAction.SignatureAction && a.sig === old) list[i] = AnnotationAction.SignatureAction(updated)
            }
        }
        swap(undoStack); swap(redoStack)
    }

    fun updatePlacedText(index: Int, updated: PlacedText) {
        val old = placedTexts.getOrNull(index) ?: return
        placedTexts[index] = updated
        fun swap(list: MutableList<AnnotationAction>) {
            for (i in list.indices) {
                val a = list[i]
                if (a is AnnotationAction.TextAction && a.text === old) list[i] = AnnotationAction.TextAction(updated)
            }
        }
        swap(undoStack); swap(redoStack)
    }

    /** Places a signature (new or saved) and selects it immediately so size / colour / rotation / position can be set. */
    fun placeNewSignature(bmp: Bitmap, scale: Float = SignatureStore.DEFAULT_PLACEMENT_SCALE) {
        val ps = PlacedSignature(signatureBitmap = bmp, x = 0.5f, y = 0.75f, scale = scale)
        placedSignatures.add(ps)
        undoStack.add(AnnotationAction.SignatureAction(ps))
        redoStack.clear()
        selectedSignatureIndex = placedSignatures.lastIndex
        activeTool = AnnotateTool.SIGNATURE
        showSignatureDialog = false
    }

    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                                contrast = contrast,
                                pageId = pageId
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
                                    updatePlacedSignature(selIdx, selected.copy(scale = newScale.coerceIn(0.12f, 0.7f)))
                                },
                                valueRange = 0.12f..0.7f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        // Rotate (fine slider + quarter turns). Applied identically on screen and when saving.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                updatePlacedSignature(selIdx, selected.copy(rotation = normalizeAngle(selected.rotation - 90f)))
                            }) { Icon(Icons.Default.RotateLeft, contentDescription = if (isArabicLocal) "تدوير لليسار" else "Rotate left") }
                            Slider(
                                value = selected.rotation,
                                onValueChange = { updatePlacedSignature(selIdx, selected.copy(rotation = it)) },
                                valueRange = -180f..180f,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                updatePlacedSignature(selIdx, selected.copy(rotation = normalizeAngle(selected.rotation + 90f)))
                            }) { Icon(Icons.Default.RotateRight, contentDescription = if (isArabicLocal) "تدوير لليمين" else "Rotate right") }
                            Text("${selected.rotation.toInt()}°", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
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
                                            updatePlacedSignature(selIdx, selected.copy(signatureBitmap = recolored))
                                        }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                val removed = placedSignatures[selIdx]
                                placedSignatures.removeAt(selIdx)
                                undoStack.add(AnnotationAction.EraseAction(listOf(AnnotationAction.SignatureAction(removed))))
                                redoStack.clear()
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
                val selTxtIdx = selectedTextIndex
                if (selTxtIdx != null && selTxtIdx in placedTexts.indices) {
                    val selectedTxt = placedTexts[selTxtIdx]
                    val isArabicLocal = context.resources.configuration.locales[0].language == "ar"
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isArabicLocal) "تعديل النص المضاف" else "Edit placed text",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { selectedTextIndex = null }) {
                                Text(if (isArabicLocal) "تم" else "Done")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FormatSize, contentDescription = null, modifier = Modifier.size(18.dp))
                            Slider(
                                value = selectedTxt.textSize,
                                onValueChange = { newSize ->
                                    updatePlacedText(selTxtIdx, selectedTxt.copy(textSize = newSize.coerceIn(24f, 120f)))
                                },
                                valueRange = 24f..120f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val colors = listOf(Color.Black, Color(0xFF1D4ED8), Color(0xFFDC2626), Color(0xFF16A34A), Color(0xFFEAB308))
                            colors.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(2.dp, if (selectedTxt.color == c.toArgb()) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                        .clickable {
                                            updatePlacedText(selTxtIdx, selectedTxt.copy(color = c.toArgb()))
                                        }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                val removed = placedTexts[selTxtIdx]
                                placedTexts.removeAt(selTxtIdx)
                                undoStack.add(AnnotationAction.EraseAction(listOf(AnnotationAction.TextAction(removed))))
                                redoStack.clear()
                                selectedTextIndex = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            if (isArabicLocal) "اسحب النص على الصفحة لتحريكه إلى المكان المطلوب"
                            else "Drag the text on the page to move it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Surface
                }
                val isArabicUi = context.resources.configuration.locales[0].language == "ar"
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeTool == AnnotateTool.BRIGHTNESS) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isArabicUi) "السطوع" else "Brightness", style = MaterialTheme.typography.labelSmall)
                                Text("${brightness.toInt()}", style = MaterialTheme.typography.labelSmall)
                            }
                            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)
                        }
                    } else if (activeTool == AnnotateTool.CONTRAST) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isArabicUi) "التباين" else "Contrast", style = MaterialTheme.typography.labelSmall)
                                Text(String.format(java.util.Locale.US, "%.2fx", contrast), style = MaterialTheme.typography.labelSmall)
                            }
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
                            label = { Text(if (isArabicUi) "سهم" else "Arrow") },
                            leadingIcon = { Icon(Icons.Default.ArrowRightAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.RECTANGLE,
                            onClick = { activeTool = AnnotateTool.RECTANGLE },
                            shape = RoundedCornerShape(50),
                            label = { Text(if (isArabicUi) "مستطيل" else "Rectangle") },
                            leadingIcon = { Icon(Icons.Default.CropSquare, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.CIRCLE,
                            onClick = { activeTool = AnnotateTool.CIRCLE },
                            shape = RoundedCornerShape(50),
                            label = { Text(if (isArabicUi) "دائرة" else "Circle") },
                            leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.TEXT,
                            onClick = { activeTool = AnnotateTool.TEXT; showTextDialog = true },
                            shape = RoundedCornerShape(50),
                            label = { Text(if (isArabicUi) "نص" else "Text") },
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
                        FilterChip(
                            selected = activeTool == AnnotateTool.BRIGHTNESS,
                            onClick = { activeTool = AnnotateTool.BRIGHTNESS },
                            shape = RoundedCornerShape(50),
                            label = { Text(if (isArabicUi) "السطوع" else "Brightness") },
                            leadingIcon = { Icon(Icons.Default.Brightness6, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = activeTool == AnnotateTool.CONTRAST,
                            onClick = { activeTool = AnnotateTool.CONTRAST },
                            shape = RoundedCornerShape(50),
                            label = { Text(if (isArabicUi) "التباين" else "Contrast") },
                            leadingIcon = { Icon(Icons.Default.Contrast, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    if (activeTool == AnnotateTool.ERASER) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = eraseMode == EraseMode.OBJECTS,
                                onClick = { eraseMode = EraseMode.OBJECTS },
                                label = { Text(if (isArabicUi) "حذف الإضافات" else "Remove additions") }
                            )
                            FilterChip(
                                selected = eraseMode == EraseMode.COVER,
                                onClick = { eraseMode = EraseMode.COVER },
                                label = { Text(if (isArabicUi) "إخفاء من الصفحة" else "Hide page content") }
                            )
                        }
                        Text(
                            if (eraseMode == EraseMode.OBJECTS) {
                                if (isArabicUi) "امسح فوق توقيع أو رسم أو نص مضاف لإزالته — تظهر الصفحة الأصلية تحته"
                                else "Rub over an added signature, drawing or text to remove it — the original page shows again"
                            } else {
                                if (isArabicUi) "يغطي جزءاً من الصفحة بلون الورق المحيط به" else "Covers part of the page with the surrounding paper colour"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                // The page is shown with its real proportions (fit inside the work area, never stretched).
                val scale = minOf(canvasW / bmpW, canvasH / bmpH) * 0.95f
                val renderW = bmpW * scale
                val renderH = bmpH * scale
                val offsetX = (canvasW - renderW) / 2f
                val offsetY = (canvasH - renderH) / 2f
                val density = androidx.compose.ui.platform.LocalDensity.current
                val eraserRadiusPx = with(density) { 14.dp.toPx() }
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                /** Paper colour around a page point: the bright (paper) end of the local luminance distribution. */
                fun samplePaperColor(nx: Float, ny: Float): Int {
                    val cx = (nx * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val cy = (ny * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    val r = (bmp.width / 60).coerceIn(6, 40)
                    val samples = ArrayList<Int>()
                    var y = (cy - r).coerceAtLeast(0)
                    while (y <= (cy + r).coerceAtMost(bmp.height - 1)) {
                        var x = (cx - r).coerceAtLeast(0)
                        while (x <= (cx + r).coerceAtMost(bmp.width - 1)) {
                            samples.add(bmp.getPixel(x, y)); x += 2
                        }
                        y += 2
                    }
                    if (samples.isEmpty()) return android.graphics.Color.WHITE
                    fun lum(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                    val sorted = samples.sortedBy { lum(it) }
                    return sorted[(sorted.size * 0.8).toInt().coerceAtMost(sorted.lastIndex)] or (0xFF shl 24)
                }

                /** Removes every added item under view point [pt]; removed items are collected for one undo step. */
                fun eraseObjectsAt(pt: Offset) {
                    fun near(px: Float, py: Float) = hypot(px - pt.x, py - pt.y) <= eraserRadiusPx
                    fun vx(n: Float) = offsetX + n * renderW
                    fun vy(n: Float) = offsetY + n * renderH
                    paths.filter { dp -> dp.points.any { near(vx(it.x), vy(it.y)) } }.forEach {
                        paths.remove(it); strokeRemovals.add(AnnotationAction.PathAction(it))
                    }
                    shapes.filter { s ->
                        val l = vx(minOf(s.startX, s.endX)) - eraserRadiusPx; val r = vx(maxOf(s.startX, s.endX)) + eraserRadiusPx
                        val tp = vy(minOf(s.startY, s.endY)) - eraserRadiusPx; val b = vy(maxOf(s.startY, s.endY)) + eraserRadiusPx
                        pt.x in l..r && pt.y in tp..b
                    }.forEach { shapes.remove(it); strokeRemovals.add(AnnotationAction.ShapeAction(it)) }
                    redactions.filter { rc ->
                        pt.x in vx(rc.left)..vx(rc.right) && pt.y in vy(rc.top)..vy(rc.bottom)
                    }.forEach { redactions.remove(it); strokeRemovals.add(AnnotationAction.RedactionAction(it)) }
                    placedSignatures.filter { sig ->
                        val (hw, hh) = placedSignatureHalfSize(sig, renderW)
                        kotlin.math.abs(pt.x - vx(sig.x)) <= hw && kotlin.math.abs(pt.y - vy(sig.y)) <= hh
                    }.forEach { placedSignatures.remove(it); strokeRemovals.add(AnnotationAction.SignatureAction(it)) }
                    placedTexts.filter { txt ->
                        val (hw, hh) = placedTextHalfSize(txt, renderW)
                        kotlin.math.abs(pt.x - vx(txt.x)) <= hw && kotlin.math.abs(pt.y - vy(txt.y)) <= hh
                    }.forEach { placedTexts.remove(it); strokeRemovals.add(AnnotationAction.TextAction(it)) }
                    selectedSignatureIndex = null
                    selectedTextIndex = null
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Tap layer: selects / deselects a placed signature or placed text.
                        .pointerInput(activeTool, placedSignatures.size, placedTexts.size, renderW, renderH, offsetX, offsetY) {
                            if (activeTool != AnnotateTool.SIGNATURE && activeTool != AnnotateTool.TEXT) return@pointerInput
                            detectTapGestures { tapPos ->
                                if (activeTool == AnnotateTool.SIGNATURE) {
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
                                } else {
                                    var hitTxt: Int? = null
                                    for (i in placedTexts.indices.reversed()) {
                                        val pt = placedTexts[i]
                                        val (halfW, halfH) = placedTextHalfSize(pt, renderW)
                                        val cx = offsetX + pt.x * renderW
                                        val cy = offsetY + pt.y * renderH
                                        if (kotlin.math.abs(tapPos.x - cx) <= halfW && kotlin.math.abs(tapPos.y - cy) <= halfH) {
                                            hitTxt = i
                                            break
                                        }
                                    }
                                    selectedTextIndex = hitTxt
                                }
                            }
                        }
                        // Move layer: drags the currently selected signature or placed text.
                        .pointerInput(activeTool, selectedSignatureIndex, selectedTextIndex, renderW, renderH, offsetX, offsetY) {
                            val sigIdx = selectedSignatureIndex
                            val txtIdx = selectedTextIndex
                            val canDragSig = activeTool == AnnotateTool.SIGNATURE && sigIdx != null && sigIdx in placedSignatures.indices
                            val canDragTxt = activeTool == AnnotateTool.TEXT && txtIdx != null && txtIdx in placedTexts.indices
                            if (!canDragSig && !canDragTxt) return@pointerInput
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (canDragSig && sigIdx != null) {
                                    val current = placedSignatures.getOrNull(sigIdx) ?: return@detectDragGestures
                                    val newX = (current.x + dragAmount.x / renderW).coerceIn(0.05f, 0.95f)
                                    val newY = (current.y + dragAmount.y / renderH).coerceIn(0.05f, 0.95f)
                                    updatePlacedSignature(sigIdx, current.copy(x = newX, y = newY))
                                } else if (canDragTxt && txtIdx != null) {
                                    val current = placedTexts.getOrNull(txtIdx) ?: return@detectDragGestures
                                    val newX = (current.x + dragAmount.x / renderW).coerceIn(0.05f, 0.95f)
                                    val newY = (current.y + dragAmount.y / renderH).coerceIn(0.05f, 0.95f)
                                    updatePlacedText(txtIdx, current.copy(x = newX, y = newY))
                                }
                            }
                        }
                        // Drawing layer: pen, highlighter, shapes, black-out and the eraser (both modes).
                        .pointerInput(activeTool, penColor, eraseMode, renderW, renderH, offsetX, offsetY) {
                            if (activeTool in listOf(AnnotateTool.SIGNATURE, AnnotateTool.TEXT, AnnotateTool.BRIGHTNESS, AnnotateTool.CONTRAST)) return@pointerInput
                            val objectEraser = activeTool == AnnotateTool.ERASER && eraseMode == EraseMode.OBJECTS
                            fun finishEraser() {
                                if (strokeRemovals.isNotEmpty()) {
                                    undoStack.add(AnnotationAction.EraseAction(strokeRemovals.toList()))
                                    redoStack.clear()
                                }
                                strokeRemovals.clear()
                                eraserFinger = null
                            }
                            detectDragGestures(
                                onDragStart = { startPt ->
                                    val nx = ((startPt.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((startPt.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    when {
                                        objectEraser -> {
                                            strokeRemovals.clear()
                                            eraserFinger = startPt
                                            eraseObjectsAt(startPt)
                                        }
                                        activeTool == AnnotateTool.REDACT -> {
                                            redactStart = Offset(nx, ny)
                                            redactEnd = Offset(nx, ny)
                                        }
                                        activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE) -> {
                                            shapeStart = Offset(nx, ny)
                                            shapeEnd = Offset(nx, ny)
                                        }
                                        else -> {
                                            if (activeTool == AnnotateTool.ERASER) {
                                                coverColor = samplePaperColor(nx, ny)
                                                eraserFinger = startPt
                                            }
                                            currentPoints.clear()
                                            currentPoints.add(StrokePoint(nx, ny))
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = ((change.position.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((change.position.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    when {
                                        objectEraser -> {
                                            eraserFinger = change.position
                                            eraseObjectsAt(change.position)
                                        }
                                        activeTool == AnnotateTool.REDACT -> redactEnd = Offset(nx, ny)
                                        activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE) -> shapeEnd = Offset(nx, ny)
                                        else -> {
                                            if (activeTool == AnnotateTool.ERASER) eraserFinger = change.position
                                            currentPoints.add(StrokePoint(nx, ny))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    if (objectEraser) finishEraser()
                                    currentPoints.clear()
                                    eraserFinger = null
                                },
                                onDragEnd = {
                                    when {
                                        objectEraser -> finishEraser()
                                        activeTool == AnnotateTool.REDACT -> {
                                            val s = redactStart
                                            val e = redactEnd
                                            if (s != null && e != null) {
                                                val r = RedactionRect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                                                redactions.add(r)
                                                undoStack.add(AnnotationAction.RedactionAction(r))
                                                redoStack.clear()
                                            }
                                            redactStart = null
                                            redactEnd = null
                                        }
                                        activeTool in listOf(AnnotateTool.ARROW, AnnotateTool.RECTANGLE, AnnotateTool.CIRCLE) -> {
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
                                        }
                                        else -> {
                                            if (currentPoints.size > 1) {
                                                val isCover = activeTool == AnnotateTool.ERASER
                                                val color = when {
                                                    activeTool == AnnotateTool.HIGHLIGHTER -> Color(0xFFFFEB3B).toArgb()
                                                    isCover -> coverColor
                                                    else -> penColor.toArgb()
                                                }
                                                val dp = DrawPath(
                                                    points = currentPoints.toList(),
                                                    color = color,
                                                    strokeWidth = if (activeTool == AnnotateTool.HIGHLIGHTER) 14f else if (isCover) 20f else 3.5f,
                                                    isHighlighter = activeTool == AnnotateTool.HIGHLIGHTER,
                                                    isEraser = isCover
                                                )
                                                paths.add(dp)
                                                undoStack.add(AnnotationAction.PathAction(dp))
                                                redoStack.clear()
                                            }
                                            currentPoints.clear()
                                            eraserFinger = null
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeScale = (renderW / 400f).coerceAtLeast(1f)
                        val liveColorFilter = if (brightness != 0f || contrast != 1f) {
                            val cScale = contrast.coerceIn(0.5f, 2.5f)
                            val effBr = if (kotlin.math.abs(brightness) <= 1.5f) brightness * 55f else brightness.coerceIn(-100f, 100f)
                            val trans = (-0.5f * cScale + 0.5f) * 255f + (effBr * 255f / 100f)
                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                androidx.compose.ui.graphics.ColorMatrix(
                                    floatArrayOf(
                                        cScale, 0f, 0f, 0f, trans,
                                        0f, cScale, 0f, 0f, trans,
                                        0f, 0f, cScale, 0f, trans,
                                        0f, 0f, 0f, 1f, 0f
                                    )
                                )
                            )
                        } else null

                        fun pathOf(points: List<StrokePoint>) = Path().apply {
                            moveTo(offsetX + points[0].x * renderW, offsetY + points[0].y * renderH)
                            for (i in 1 until points.size) lineTo(offsetX + points[i].x * renderW, offsetY + points[i].y * renderH)
                        }

                        /** Page + every annotation. Drawn once normally and once inside the eraser magnifier. */
                        fun drawScene() {
                            drawImage(
                                image = imageBitmap,
                                dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                                dstSize = IntSize(renderW.toInt(), renderH.toInt()),
                                colorFilter = liveColorFilter
                            )
                            // Cover strokes belong to the page: same colour filter as the page, under every annotation.
                            for (dp in paths) {
                                if (!dp.isEraser || dp.points.size < 2) continue
                                drawPath(
                                    pathOf(dp.points), Color(dp.color),
                                    style = Stroke(width = dp.strokeWidth * strokeScale, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                                    colorFilter = liveColorFilter
                                )
                            }
                            if (activeTool == AnnotateTool.ERASER && eraseMode == EraseMode.COVER && currentPoints.size > 1) {
                                drawPath(
                                    pathOf(currentPoints), Color(coverColor),
                                    style = Stroke(width = 20f * strokeScale, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                                    colorFilter = liveColorFilter
                                )
                            }
                            for (dp in paths) {
                                if (dp.isEraser || dp.points.size < 2) continue
                                drawPath(
                                    path = pathOf(dp.points),
                                    color = Color(dp.color).copy(alpha = if (dp.isHighlighter) 0.45f else 1.0f),
                                    style = Stroke(width = dp.strokeWidth * strokeScale, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                                )
                            }
                            if (activeTool != AnnotateTool.ERASER && currentPoints.size > 1) {
                                val previewColor = if (activeTool == AnnotateTool.HIGHLIGHTER) Color(0xFFFFEB3B).copy(alpha = 0.45f) else penColor
                                val previewWidth = (if (activeTool == AnnotateTool.HIGHLIGHTER) 14f else 3.5f) * strokeScale
                                drawPath(
                                    path = pathOf(currentPoints),
                                    color = previewColor,
                                    style = Stroke(width = previewWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                                )
                            }
                            fun drawShapeOf(type: ShapeType, sx: Float, sy: Float, ex: Float, ey: Float, color: Color, width: Float) {
                                when (type) {
                                    ShapeType.RECTANGLE -> drawRect(
                                        color = color,
                                        topLeft = Offset(minOf(sx, ex), minOf(sy, ey)),
                                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy)),
                                        style = Stroke(width = width)
                                    )
                                    ShapeType.CIRCLE -> drawOval(
                                        color = color,
                                        topLeft = Offset(minOf(sx, ex), minOf(sy, ey)),
                                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy)),
                                        style = Stroke(width = width)
                                    )
                                    ShapeType.ARROW -> {
                                        drawLine(color = color, start = Offset(sx, sy), end = Offset(ex, ey), strokeWidth = width)
                                        // Arrow head, same geometry as AnnotationEngine (what you see is what is saved).
                                        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                                        val len = 28f * (renderW / 400f)
                                        val a = Math.toRadians(25.0)
                                        drawLine(color, Offset(ex, ey), Offset((ex - len * cos(angle - a)).toFloat(), (ey - len * sin(angle - a)).toFloat()), width)
                                        drawLine(color, Offset(ex, ey), Offset((ex - len * cos(angle + a)).toFloat(), (ey - len * sin(angle + a)).toFloat()), width)
                                    }
                                }
                            }
                            for (s in shapes) {
                                drawShapeOf(
                                    s.type, offsetX + s.startX * renderW, offsetY + s.startY * renderH,
                                    offsetX + s.endX * renderW, offsetY + s.endY * renderH, Color(s.color), s.strokeWidth * (renderW / 400f)
                                )
                            }
                            val ss = shapeStart
                            val se = shapeEnd
                            if (ss != null && se != null) {
                                val type = when (activeTool) {
                                    AnnotateTool.RECTANGLE -> ShapeType.RECTANGLE
                                    AnnotateTool.CIRCLE -> ShapeType.CIRCLE
                                    else -> ShapeType.ARROW
                                }
                                drawShapeOf(
                                    type, offsetX + ss.x * renderW, offsetY + ss.y * renderH,
                                    offsetX + se.x * renderW, offsetY + se.y * renderH, penColor, 4f * (renderW / 400f)
                                )
                            }
                            // Placed signatures (rotation applied exactly like when saving); the selected one is outlined.
                            for ((i, sig) in placedSignatures.withIndex()) {
                                val sigW = (renderW * sig.scale).toInt().coerceAtLeast(1)
                                val sigH = (sigW * (sig.signatureBitmap.height.toFloat() / sig.signatureBitmap.width.toFloat())).toInt().coerceAtLeast(1)
                                val cx = offsetX + sig.x * renderW
                                val cy = offsetY + sig.y * renderH
                                rotate(sig.rotation, pivot = Offset(cx, cy)) {
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
                            }
                            for ((idx, pt) in placedTexts.withIndex()) {
                                val cx = offsetX + pt.x * renderW
                                val cy = offsetY + pt.y * renderH
                                drawContext.canvas.nativeCanvas.drawText(
                                    pt.text, cx, cy,
                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        color = pt.color
                                        textSize = pt.textSize * (renderW / 1080f)
                                    }
                                )
                                if (idx == selectedTextIndex) {
                                    val (halfW, halfH) = placedTextHalfSize(pt, renderW)
                                    drawRect(
                                        color = GoldBase,
                                        topLeft = Offset(cx - halfW - 6f, cy - halfH * 1.4f - 4f),
                                        size = androidx.compose.ui.geometry.Size(halfW * 2f + 12f, halfH * 2f + 8f),
                                        style = Stroke(width = 2.2f)
                                    )
                                }
                            }
                            for (r in redactions) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = Offset(offsetX + r.left * renderW, offsetY + r.top * renderH),
                                    size = androidx.compose.ui.geometry.Size((r.right - r.left) * renderW, (r.bottom - r.top) * renderH)
                                )
                            }
                            val rs = redactStart
                            val re = redactEnd
                            if (rs != null && re != null) {
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.7f),
                                    topLeft = Offset(offsetX + minOf(rs.x, re.x) * renderW, offsetY + minOf(rs.y, re.y) * renderH),
                                    size = androidx.compose.ui.geometry.Size(kotlin.math.abs(re.x - rs.x) * renderW, kotlin.math.abs(re.y - rs.y) * renderH)
                                )
                            }
                        }

                        drawScene()
                        // Hairline page border: separates the real page (any paper colour) from the work area.
                        drawRect(
                            color = Color.White.copy(alpha = 0.18f),
                            topLeft = Offset(offsetX, offsetY),
                            size = androidx.compose.ui.geometry.Size(renderW, renderH),
                            style = Stroke(width = 1.dp.toPx())
                        )

                        // Eraser magnifier: an enlarged view of the area under the finger, shown ABOVE the finger
                        // (below it near the top edge) so it never hides the spot being erased. Display only:
                        // erasing always uses the real finger position, never loupe coordinates.
                        val finger = eraserFinger
                        if (activeTool == AnnotateTool.ERASER && finger != null) {
                            val radius = 58.dp.toPx()
                            val gap = 64.dp.toPx()
                            val zoom = 2.5f
                            val lx = finger.x.coerceIn(radius + 8f, size.width - radius - 8f)
                            var ly = finger.y - gap - radius
                            if (ly < radius + 8f) ly = finger.y + gap + radius
                            ly = ly.coerceIn(radius + 8f, size.height - radius - 8f)
                            val center = Offset(lx, ly)
                            drawCircle(Color.Black, radius = radius, center = center)
                            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }) {
                                withTransform({
                                    translate(lx - finger.x * zoom, ly - finger.y * zoom)
                                    scale(zoom, zoom, pivot = Offset.Zero)
                                }) { drawScene() }
                            }
                            drawCircle(Color.White, radius = eraserRadiusPx * zoom, center = center, style = Stroke(width = 1.5.dp.toPx()))
                            drawCircle(GoldBase, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
                            // Footprint at the real finger position.
                            drawCircle(Color.White, radius = eraserRadiusPx, center = finger, style = Stroke(width = 1.5.dp.toPx()))
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

    // Full-screen signature pad (same corrected rendering as "My signatures"); the result is saved to the
    // vault for reuse and placed on the page.
    if (showFullSignaturePad) {
        val isArabicPad = context.resources.configuration.locales[0].language == "ar"
        com.example.ui.screens.settings.FullScreenSignatureWorkspace(
            isArabic = isArabicPad,
            title = if (isArabicPad) "توقيع جديد" else "New signature",
            onCancel = { showFullSignaturePad = false },
            onSave = { sigBmp ->
                viewModel.saveSignatureToVault(if (isArabicPad) "توقيع" else "Signature", sigBmp)
                showFullSignaturePad = false
                placeNewSignature(sigBmp)
            }
        )
    }
    }
    if (showTextDialog) {
        val isArabicDialog = context.resources.configuration.locales[0].language == "ar"
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(if (isArabicDialog) "إضافة نص" else "Add Text", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempTextInput,
                    onValueChange = { tempTextInput = it },
                    label = { Text(if (isArabicDialog) "أدخل النص" else "Enter text") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempTextInput.isNotBlank()) {
                            val stagger = (placedTexts.size % 5) * 0.05f
                            val pt = PlacedText(
                                text = tempTextInput.trim(),
                                x = 0.5f,
                                y = (0.45f + stagger).coerceIn(0.15f, 0.85f),
                                color = penColor.toArgb()
                            )
                            placedTexts.add(pt)
                            undoStack.add(AnnotationAction.TextAction(pt))
                            redoStack.clear()
                            selectedTextIndex = placedTexts.lastIndex
                            activeTool = AnnotateTool.TEXT
                            tempTextInput = ""
                        }
                        showTextDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (isArabicDialog) "إضافة" else "Add", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
    if (showSignatureDialog) {
        // One list per finger stroke (the previous single list joined separate strokes with straight lines)
        // with touch timestamps for SignatureCorrection (smoothing + pen-pressure width), and the real pad
        // size (the previous fixed 400x200 bitmap clipped everything drawn outside that area).
        val signatureStrokes = remember { mutableStateListOf<List<SignatureCorrection.TimedPoint>>() }
        val currentSigStroke = remember { mutableStateListOf<SignatureCorrection.TimedPoint>() }
        var signaturePadSize by remember { mutableStateOf(IntSize.Zero) }
        val isArabic = context.resources.configuration.locales[0].language == "ar"

        /** Places a signature bitmap (new or from the vault) and immediately selects it for editing,
         *  so Change Color / Resize / Move are available right away. */
        fun placeAndSelect(bmp: Bitmap, scale: Float = SignatureStore.DEFAULT_PLACEMENT_SCALE) = placeNewSignature(bmp, scale)
        // The default signature (chosen in Settings > My signatures) is offered first.
        val defaultSigId = remember { SignatureStore.defaultSignatureId(context) }
        val orderedSignatures = remember(uiState.savedSignatures, defaultSigId) {
            uiState.savedSignatures.sortedByDescending { it.id == defaultSigId }
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
                            items(orderedSignatures, key = { it.id }) { sig ->
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
                                                if (bmp != null) placeAndSelect(bmp, SignatureStore.placementScale(context, sig.imagePath))
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = File(sig.imagePath),
                                        contentDescription = sig.title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(4.dp)
                                    )
                                    if (sig.id == defaultSigId) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = if (isArabic) "التوقيع الافتراضي" else "Default signature",
                                            tint = GoldBase,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp)
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
                            .onSizeChanged { signaturePadSize = it }
                            .pointerInput(Unit) {
                                fun now() = android.os.SystemClock.uptimeMillis()
                                detectDragGestures(
                                    onDragStart = {
                                        currentSigStroke.clear()
                                        currentSigStroke.add(SignatureCorrection.TimedPoint(it.x, it.y, now()))
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentSigStroke.add(SignatureCorrection.TimedPoint(change.position.x, change.position.y, now()))
                                    },
                                    onDragEnd = {
                                        if (currentSigStroke.size > 1) signatureStrokes.add(currentSigStroke.toList())
                                        currentSigStroke.clear()
                                    },
                                    onDragCancel = {
                                        if (currentSigStroke.size > 1) signatureStrokes.add(currentSigStroke.toList())
                                        currentSigStroke.clear()
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            fun drawStroke(pts: List<SignatureCorrection.TimedPoint>) {
                                if (pts.size < 2) return
                                val p = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                }
                                drawPath(
                                    p, Color.Black,
                                    style = Stroke(
                                        width = 4f,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                                    )
                                )
                            }
                            signatureStrokes.forEach { drawStroke(it) }
                            drawStroke(currentSigStroke)
                        }
                    }
                    TextButton(
                        onClick = { signatureStrokes.clear(); currentSigStroke.clear() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.txt_clear_signature))
                    }
                    OutlinedButton(
                        onClick = { showSignatureDialog = false; showFullSignaturePad = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isArabic) "رسم في شاشة كاملة" else "Draw full screen")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (currentSigStroke.size > 1) signatureStrokes.add(currentSigStroke.toList())
                        currentSigStroke.clear()
                        val pad = signaturePadSize
                        val strokes = signatureStrokes.toList()
                        if (strokes.isNotEmpty() && pad.width > 0 && pad.height > 0) {
                            coroutineScope.launch {
                                // Same rendering as "My signatures": smoothed, pressure width, transparent, trimmed.
                                val sigBmp = withContext(Dispatchers.Default) {
                                    SignatureCorrection.render(
                                        strokes.map { SignatureCorrection.CorrectedStroke(it, android.graphics.Color.BLACK) },
                                        pad.width, pad.height
                                    )
                                }
                                if (sigBmp != null) {
                                    viewModel.saveSignatureToVault("Signature", sigBmp)
                                    placeAndSelect(sigBmp)
                                } else {
                                    showSignatureDialog = false
                                }
                            }
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

/** Angle in -180..180 (slider range). */
private fun normalizeAngle(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}
