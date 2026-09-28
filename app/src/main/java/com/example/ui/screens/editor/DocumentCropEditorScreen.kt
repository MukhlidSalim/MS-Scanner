package com.example.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.engine.cv.QuadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class EditorTab { CROP, FILTERS, ADJUST }

data class EditorState(
    val quad: DocumentQuad,
    val rotation: Int,
    val filter: FilterType?,
    val brightness: Float,
    val contrast: Float,
    val sharpen: Boolean
)

/** Full result of a crop session, for callers that persist cropQuadJson / rotationDegrees / filterType. */
data class CropEditorResult(
    val processedPath: String,
    val rawPath: String,
    /** Normalized to the RAW image. */
    val quad: DocumentQuad,
    val rotationDegrees: Int,
    val filter: FilterType?
)

private enum class DetectionStatus { LOADING, DETECTING, DETECTED, NOT_FOUND, STORED, MANUAL }

private val Accent = Color(0xFF34D399)
private val CanvasBg = Color(0xFF111418)

/** Brightness slider range passed to ImageProcessor.adjustEnhancements. Keep in sync with that function. */
private val BRIGHTNESS_RANGE = -1f..1f
private val CONTRAST_RANGE = 0.6f..2.0f

/**
 * Crop / perspective / filter editor.
 *
 * [imagePath] MUST be the RAW (uncropped, upright) image. The initial quad is, in order:
 * [initialQuad] -> quad persisted by the pipeline for this raw file -> fresh detection -> full image.
 * The image is shown immediately; detection never blocks editing, and when it fails the full-image
 * quad is editable by hand (manual fallback).
 *
 * Coordinate model: `quad` state is always RAW-normalized. The canvas shows the raw image rotated by
 * `rotation`, so the quad is converted with DocumentQuad.rotated() for display and back for gestures.
 * Save renders raw -> warp(quad) -> rotate -> filter -> enhancements via DocumentPipeline.render().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentCropEditorScreen(
    imagePath: String,
    onCropped: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuad: DocumentQuad? = null,
    initialRotation: Int = 0,
    initialFilter: FilterType? = DocumentPipeline.DEFAULT_FILTER,
    expectedAspectRatio: Float? = null,
    onCropResult: ((CropEditorResult) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en

    var rawBitmap by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    var previewRaw by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    var displayImage by remember(imagePath) { mutableStateOf<ImageBitmap?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }

    var selectedTab by remember { mutableStateOf(EditorTab.CROP) }
    var quad by remember(imagePath) { mutableStateOf(DocumentQuad.fullQuad()) }
    var rotation by remember(imagePath) { mutableStateOf(normalizeRotation(initialRotation)) }
    var filter by remember(imagePath) { mutableStateOf(initialFilter) }
    var brightness by remember(imagePath) { mutableStateOf(0f) }
    var contrast by remember(imagePath) { mutableStateOf(1f) }
    var sharpen by remember(imagePath) { mutableStateOf(false) }
    var status by remember(imagePath) { mutableStateOf(DetectionStatus.LOADING) }
    var userEdited by remember(imagePath) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val undoStack = remember(imagePath) { mutableStateListOf<EditorState>() }
    val redoStack = remember(imagePath) { mutableStateListOf<EditorState>() }

    fun snapshot() = EditorState(quad, rotation, filter, brightness, contrast, sharpen)
    fun restore(s: EditorState) {
        quad = s.quad; rotation = s.rotation; filter = s.filter
        brightness = s.brightness; contrast = s.contrast; sharpen = s.sharpen
    }
    fun pushHistory() {
        undoStack.add(snapshot())
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }

    // ---- Load raw image and initial quad (never blocks the UI) -------------------------------
    LaunchedEffect(imagePath) {
        status = DetectionStatus.LOADING
        val loaded = withContext(Dispatchers.IO) {
            runCatching { ImageProcessor.loadBitmapFromFile(imagePath, 2048) }.getOrNull()
        }
        if (loaded == null) {
            snackbar.showSnackbar(t("Unable to open image", "تعذر فتح الصورة"))
            return@LaunchedEffect
        }
        rawBitmap = loaded
        previewRaw = withContext(Dispatchers.Default) { downscale(loaded, 1200) }

        val known = initialQuad ?: withContext(Dispatchers.IO) { QuadStore.load(imagePath) }
        if (known != null) {
            quad = known.clamped()
            status = DetectionStatus.STORED
        } else {
            quad = DocumentQuad.fullQuad()
            status = DetectionStatus.DETECTING
            val detection = runCatching { DocumentPipeline.detectOnRaw(loaded, expectedAspectRatio) }.getOrNull()
            if (!userEdited) {
                if (detection != null) {
                    quad = detection.quad
                    status = DetectionStatus.DETECTED
                } else {
                    status = DetectionStatus.NOT_FOUND
                    snackbar.showSnackbar(t("Document edges not found — drag the corners to crop", "لم يتم العثور على حواف المستند — اسحب الزوايا للقص"))
                }
            }
        }
    }

    // ---- Displayed (rotated) image -------------------------------------------------------------
    LaunchedEffect(rawBitmap, rotation) {
        val raw = rawBitmap ?: return@LaunchedEffect
        displayImage = withContext(Dispatchers.Default) {
            (if (rotation == 0) raw else rotateBitmap(raw, rotation)).asImageBitmap()
        }
    }

    // ---- Live preview for Filters / Adjust tabs (debounced, off main thread) --------------------
    LaunchedEffect(selectedTab, quad, rotation, filter, brightness, contrast, sharpen, previewRaw) {
        if (selectedTab == EditorTab.CROP) return@LaunchedEffect
        val src = previewRaw ?: return@LaunchedEffect
        delay(120)
        try {
            val out = DocumentPipeline.renderBitmap(src, quad, rotation, filter, brightness, contrast, sharpen)
            previewImage = out.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    DisposableEffect(imagePath) {
        onDispose {
            // Bitmaps are released by GC once no longer referenced; explicit recycle here could race a
            // frame that is still drawing them.
            rawBitmap = null
            previewRaw = null
        }
    }

    fun runAutoDetect(recordHistory: Boolean = true) {
        val raw = rawBitmap ?: return
        scope.launch {
            status = DetectionStatus.DETECTING
            val detection = runCatching { DocumentPipeline.detectOnRaw(raw, expectedAspectRatio) }.getOrNull()
            if (detection != null) {
                if (recordHistory) pushHistory()
                quad = detection.quad
                status = DetectionStatus.DETECTED
            } else {
                status = DetectionStatus.NOT_FOUND
                snackbar.showSnackbar(t("No document detected — adjust manually", "لم يتم اكتشاف مستند — عدّل يدويًا"))
            }
        }
    }

    fun save() {
        if (isSaving || rawBitmap == null) return
        isSaving = true
        scope.launch {
            try {
                val path = DocumentPipeline.render(context, imagePath, quad, rotation, filter, brightness, contrast, sharpen)
                if (path.isNullOrBlank()) {
                    snackbar.showSnackbar(t("Saving failed", "فشل الحفظ"))
                } else {
                    onCropResult?.invoke(CropEditorResult(path, imagePath, quad, rotation, filter))
                    onCropped(path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                snackbar.showSnackbar(t("Saving failed", "فشل الحفظ"))
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (status) {
                            DetectionStatus.LOADING -> t("Loading…", "جارٍ التحميل…")
                            DetectionStatus.DETECTING -> t("Detecting edges…", "جارٍ اكتشاف الحواف…")
                            DetectionStatus.NOT_FOUND -> t("Manual crop", "قص يدوي")
                            else -> t("Crop & Enhance", "قص وتحسين")
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back", "رجوع"))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val prev = undoStack.removeLastOrNull() ?: return@IconButton
                            redoStack.add(snapshot())
                            restore(prev)
                        },
                        enabled = undoStack.isNotEmpty()
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                    IconButton(
                        onClick = {
                            val next = redoStack.removeLastOrNull() ?: return@IconButton
                            undoStack.add(snapshot())
                            restore(next)
                        },
                        enabled = redoStack.isNotEmpty()
                    ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                    IconButton(onClick = {
                        pushHistory()
                        rotation = 0
                        filter = initialFilter
                        brightness = 0f
                        contrast = 1f
                        sharpen = false
                        userEdited = false
                        runAutoDetect(recordHistory = false)
                    }) { Icon(Icons.Default.RestartAlt, contentDescription = t("Reset", "إعادة ضبط")) }
                    Button(
                        onClick = { save() },
                        enabled = !isSaving && rawBitmap != null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isSaving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Text(t("Save", "حفظ"), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column {
                    when (selectedTab) {
                        EditorTab.CROP -> Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { runAutoDetect() }, enabled = status != DetectionStatus.DETECTING && rawBitmap != null) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Accent)
                                Spacer(Modifier.width(6.dp))
                                Text(t("Auto", "تلقائي"), color = Accent, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(onClick = {
                                pushHistory()
                                quad = DocumentQuad.fullQuad()
                                userEdited = true
                                status = DetectionStatus.MANUAL
                            }) {
                                Icon(Icons.Default.CropFree, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("Full", "كامل"), fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { pushHistory(); rotation = normalizeRotation(rotation - 90) }) {
                                Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = t("Rotate left", "تدوير لليسار"))
                            }
                            IconButton(onClick = { pushHistory(); rotation = normalizeRotation(rotation + 90) }) {
                                Icon(Icons.Default.Rotate90DegreesCw, contentDescription = t("Rotate right", "تدوير لليمين"))
                            }
                        }
                        EditorTab.FILTERS -> Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = filter == null,
                                onClick = { if (filter != null) { pushHistory(); filter = null } },
                                label = { Text(t("Original", "الأصل")) }
                            )
                            for (f in FilterType.values()) {
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { if (filter != f) { pushHistory(); filter = f } },
                                    label = { Text(prettyName(f.name)) }
                                )
                            }
                        }
                        EditorTab.ADJUST -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            LabeledSlider(t("Brightness", "السطوع"), brightness, BRIGHTNESS_RANGE, { brightness = it }, { pushHistory() })
                            LabeledSlider(t("Contrast", "التباين"), contrast, CONTRAST_RANGE, { contrast = it }, { pushHistory() })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("Sharpen", "زيادة الحدة"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Switch(checked = sharpen, onCheckedChange = { pushHistory(); sharpen = it })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.height(56.dp)) {
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.CROP,
                            onClick = { selectedTab = EditorTab.CROP },
                            icon = { Icon(Icons.Default.Crop, contentDescription = null) },
                            label = { Text(t("Crop", "قص"), fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.FILTERS,
                            onClick = { selectedTab = EditorTab.FILTERS },
                            icon = { Icon(Icons.Default.ColorLens, contentDescription = null) },
                            label = { Text(t("Filters", "الفلاتر"), fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == EditorTab.ADJUST,
                            onClick = { selectedTab = EditorTab.ADJUST },
                            icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            label = { Text(t("Adjust", "ضبط"), fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).background(CanvasBg),
            contentAlignment = Alignment.Center
        ) {
            val shown = displayImage
            when {
                shown == null -> CircularProgressIndicator(color = Accent)
                selectedTab == EditorTab.CROP -> QuadCropEditor(
                    image = shown,
                    // RAW-normalized -> displayed (rotated) frame.
                    quad = quad.rotated(rotation),
                    onQuadChange = { displayed ->
                        quad = displayed.rotated(360 - rotation)
                        userEdited = true
                    },
                    onDragStart = { pushHistory() },
                    onDragEnd = { if (status != DetectionStatus.DETECTED && status != DetectionStatus.STORED) status = DetectionStatus.MANUAL },
                    modifier = Modifier.fillMaxSize(),
                    accent = Accent
                )
                else -> {
                    val p = previewImage
                    if (p == null) CircularProgressIndicator(color = Accent)
                    else Image(bitmap = p, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
                }
            }
            if (status == DetectionStatus.DETECTING && selectedTab == EditorTab.CROP) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("Detecting edges…", "جارٍ اكتشاف الحواف…"), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onStart: () -> Unit
) {
    var started by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(
            value = value,
            onValueChange = {
                if (!started) { started = true; onStart() } // one history entry per slider gesture
                onChange(it)
            },
            onValueChangeFinished = { started = false },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(String.format("%.1f", value), fontSize = 11.sp, modifier = Modifier.width(32.dp))
    }
}

private fun normalizeRotation(deg: Int): Int = ((deg % 360) + 360) % 360

private fun prettyName(name: String): String =
    name.lowercase().split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

/** Clockwise rotation; must match DocumentQuad.rotated() and DocumentPipeline rendering. */
private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap = DocumentPipeline.rotate(src, degrees)

private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= maxSide) return src
    val s = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
}
