package com.example.ui.screens.settings

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.SignatureEntity
import com.example.data.repository.DocumentRepository
import com.example.engine.annotation.SignatureCorrection
import com.example.engine.annotation.SignatureStore
import com.example.ui.theme.GoldBase
import com.example.ui.theme.PaperSurface1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One recorded touch sample with its capture time (needed for speed-based pressure simulation). */
private data class StrokePt(val x: Float, val y: Float, val tMs: Long = SystemClock.elapsedRealtime())

private data class DrawnStroke(val points: List<StrokePt>, val color: Int)

/**
 * Reusable signature vault (Settings -> Signatures): create, edit (name, colour, rotation, default size),
 * set as default, redraw, delete.
 *
 * ROOT CAUSE of "the bottom buttons of the signature page are cut off": this screen and the drawing pad were
 * Dialog windows with decorFitsSystemWindows = false. Window insets are not reliably dispatched to a Dialog's
 * own window, so navigationBarsPadding() measured 0 and the bottom actions slid under the system navigation
 * bar. They are now full-screen layers inside the edge-to-edge Activity window, padded with
 * WindowInsets.safeDrawing (status bar, navigation bar, display cutout AND keyboard), and the primary action
 * is a regular bottom bar instead of a FAB floating over the list.
 *
 * Must be placed in a full-size Box (SettingsScreen / AnnotationScreen do this) so it covers the screen.
 */
@Composable
fun SignatureStudioScreen(
    repository: DocumentRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val scope = rememberCoroutineScope()
    val signatures by repository.getAllSignatures().collectAsState(initial = emptyList())
    var showWorkspace by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<SignatureEntity?>(null) }
    var editing by remember { mutableStateOf<SignatureEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SignatureEntity?>(null) }
    var defaultId by remember { mutableLongStateOf(SignatureStore.defaultSignatureId(context)) }
    // Bumped after an in-place edit so the cards reload the edited PNG.
    var refreshKey by remember { mutableIntStateOf(0) }

    BackHandler(enabled = !showWorkspace && editing == null) { onDismiss() }

    Surface(modifier = Modifier.fillMaxSize().zIndex(10f), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back", "رجوع")) }
                Text(t("My signatures", "توقيعاتي"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (signatures.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Draw, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t(
                            "Create your signature once, then add it to any document from the Sign tool.",
                            "أنشئ توقيعك مرة واحدة، ثم أضفه إلى أي مستند من أداة التوقيع."
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    t("Tap a signature to edit it. ★ = offered first when signing.", "اضغط على توقيع لتعديله. ★ = يُعرض أولاً عند التوقيع."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(signatures, key = { it.id }) { sig ->
                        SignatureCard(
                            signature = sig,
                            isDefault = sig.id == defaultId,
                            refreshKey = refreshKey,
                            isArabic = isArabic,
                            onEdit = { editing = sig },
                            onToggleDefault = {
                                val newId = if (sig.id == defaultId) 0L else sig.id
                                SignatureStore.setDefaultSignatureId(context, newId)
                                defaultId = newId
                            },
                            onReplace = { replacing = sig; showWorkspace = true },
                            onDelete = { pendingDelete = sig }
                        )
                    }
                }
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { replacing = null; showWorkspace = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(50.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("New signature", "توقيع جديد"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    editing?.let { sig ->
        SignatureEditorScreen(
            signature = sig,
            isArabic = isArabic,
            onDismiss = { editing = null },
            onSaved = { updated ->
                scope.launch { repository.updateSignature(updated) }
                refreshKey++
                editing = null
            }
        )
    }

    if (showWorkspace) {
        FullScreenSignatureWorkspace(
            isArabic = isArabic,
            title = if (replacing != null) t("Redraw signature", "إعادة رسم التوقيع") else t("New signature", "توقيع جديد"),
            onCancel = { showWorkspace = false },
            onSave = { bitmap ->
                scope.launch {
                    val path = SignatureStore.savePng(context, bitmap)
                    val old = replacing
                    if (old != null) {
                        // Redraw keeps the name, the default flag and the placement size of the old signature.
                        SignatureStore.setPlacementScale(context, path, SignatureStore.placementScale(context, old.imagePath))
                        repository.deleteSignature(old.id)
                        SignatureStore.delete(old.imagePath)
                        val newId = repository.saveSignature(old.title, path)
                        if (old.id == defaultId) {
                            SignatureStore.setDefaultSignatureId(context, newId)
                            defaultId = newId
                        }
                    } else {
                        repository.saveSignature(t("Signature", "توقيع"), path)
                    }
                    showWorkspace = false
                    replacing = null
                }
            }
        )
    }

    pendingDelete?.let { sig ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(t("Delete this signature?", "حذف هذا التوقيع؟")) },
            text = { Text(t("This cannot be undone. Documents already signed keep their signature.", "لا يمكن التراجع عن هذا. المستندات الموقعة سابقًا تحتفظ بتوقيعها.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteSignature(sig.id)
                        SignatureStore.delete(sig.imagePath)
                        if (sig.id == defaultId) {
                            SignatureStore.setDefaultSignatureId(context, 0L)
                            defaultId = 0L
                        }
                    }
                    pendingDelete = null
                }) { Text(t("Delete", "حذف"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(t("Cancel", "إلغاء")) } }
        )
    }
}

@Composable
private fun SignatureCard(
    signature: SignatureEntity,
    isDefault: Boolean,
    refreshKey: Int,
    isArabic: Boolean,
    onEdit: () -> Unit,
    onToggleDefault: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var bitmap by remember(signature.imagePath, refreshKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(signature.imagePath, refreshKey) {
        bitmap = withContext(Dispatchers.IO) { SignatureStore.loadDisplayable(signature.imagePath, 600) }
    }
    var showMenu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (isDefault) 1.5.dp else 1.dp, if (isDefault) GoldBase else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onEdit)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    // Signatures are dark ink with a transparent background: shown on paper colour in both themes.
                    .background(PaperSurface1),
                contentAlignment = Alignment.Center
            ) {
                val b = bitmap
                if (b != null) {
                    androidx.compose.foundation.Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = signature.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(10.dp)
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (isDefault) {
                    Icon(Icons.Default.Star, t("Default", "افتراضي"), tint = GoldBase, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    signature.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, t("Options", "خيارات"), modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(t("Edit (colour, size, rotation)", "تعديل (اللون، الحجم، الدوران)")) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isDefault) t("Remove as default", "إلغاء الافتراضي") else t("Set as default", "تعيين كافتراضي")) },
                            leadingIcon = { Icon(if (isDefault) Icons.Default.StarBorder else Icons.Default.Star, null, tint = GoldBase) },
                            onClick = { showMenu = false; onToggleDefault() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("Redraw", "إعادة الرسم")) },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                            onClick = { showMenu = false; onReplace() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("Delete", "حذف"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Edits a saved signature in place: name, ink colour, rotation and the default size used when it is placed
 * on a page. The transparent PNG is re-rendered from the stored file (tint keeps the alpha mask, rotation
 * uses an enlarged transparent canvas, the result is trimmed to the ink) and written back to the same file,
 * so the database row, the default flag and every reference stay valid.
 */
@Composable
private fun SignatureEditorScreen(
    signature: SignatureEntity,
    isArabic: Boolean,
    onDismiss: () -> Unit,
    onSaved: (SignatureEntity) -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var name by remember { mutableStateOf(signature.title) }
    var color by remember { mutableStateOf<Int?>(null) } // null = keep the current ink colour
    var rotation by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(SignatureStore.placementScale(context, signature.imagePath)) }
    var saving by remember { mutableStateOf(false) }

    BackHandler { if (!saving) onDismiss() }
    LaunchedEffect(signature.imagePath) {
        original = withContext(Dispatchers.IO) { SignatureStore.loadDisplayable(signature.imagePath, 1600) }
    }
    LaunchedEffect(original, color, rotation) {
        val src = original ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            val tinted = color?.let { SignatureStore.tinted(src, it) } ?: src
            SignatureStore.rotated(tinted, rotation).also { if (it !== tinted && tinted !== src) tinted.recycle() }
        }
    }

    Surface(modifier = Modifier.fillMaxSize().zIndex(20f), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!saving) onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Cancel", "إلغاء")) }
                Text(t("Edit signature", "تعديل التوقيع"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Preview on a page mock-up: the signature width matches the chosen placement size.
                Text(t("Preview on a page", "معاينة على الصفحة"), style = MaterialTheme.typography.labelLarge)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PaperSurface1)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val p = preview
                    if (p != null) {
                        androidx.compose.foundation.Image(
                            bitmap = p.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(scale.coerceIn(0.12f, 0.7f) / 0.7f * 0.9f)
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text(t("Name", "الاسم")) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(t("Ink colour", "لون الحبر"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SignatureStore.DEFAULT_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (color == c) 3.dp else 1.dp, if (color == c) GoldBase else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                Text(t("Rotation", "الدوران") + ": ${rotation.toInt()}°", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { rotation = ((rotation - 90f + 540f) % 360f) - 180f }) { Icon(Icons.Default.RotateLeft, t("Rotate left", "تدوير لليسار")) }
                    Slider(value = rotation, onValueChange = { rotation = it }, valueRange = -180f..180f, modifier = Modifier.weight(1f))
                    IconButton(onClick = { rotation = ((rotation + 90f + 540f) % 360f) - 180f }) { Icon(Icons.Default.RotateRight, t("Rotate right", "تدوير لليمين")) }
                }
                Text(
                    t("Default size on the page", "الحجم الافتراضي على الصفحة") + ": ${(scale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.12f..0.7f)
                Text(
                    t("The size can still be adjusted on each document.", "يمكن تعديل الحجم أيضاً على كل مستند."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val p = preview ?: return@Button
                        saving = true
                        scope.launch {
                            val changedPixels = color != null || rotation != 0f
                            val ok = !changedPixels || SignatureStore.overwritePng(signature.imagePath, p)
                            SignatureStore.setPlacementScale(context, signature.imagePath, scale)
                            saving = false
                            if (ok) onSaved(signature.copy(title = name.trim().ifBlank { signature.title }))
                        }
                    },
                    enabled = !saving && preview != null,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(50.dp)
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(t("Save changes", "حفظ التعديلات"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen, dedicated drawing workspace: the entire screen, minus a compact top bar and a slim
 * colour/action bar, is the drawing surface — no small fixed-height box. The canvas keeps a true
 * transparent background (no card, no white box) — only strokes are stored.
 *
 * ADVANCED SIGNATURE AUTO-CORRECTION: every recorded touch point keeps a timestamp
 * (`SystemClock.elapsedRealtime()`), and the final bitmap is NOT a 1:1 capture of the raw touch path.
 * Instead [SignatureCorrection.render] runs a three-stage pipeline (denoise jitter -> fit a Catmull-Rom
 * spline through the cleaned points -> render with a variable stroke width that simulates real pen
 * pressure from drawing speed, tapering to a point at every stroke's start/end). See
 * engine/annotation/SignatureCorrection.kt for the full algorithm. The live on-screen preview still
 * draws the simple raw path while the user is actively drawing (so input feels instant / zero-latency);
 * the correction is applied once, at Save time, to the saved/placed bitmap.
 */
@Composable
fun FullScreenSignatureWorkspace(
    isArabic: Boolean,
    title: String,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    val currentPoints = remember { mutableStateListOf<StrokePt>() }
    var selectedColor by remember { mutableStateOf(SignatureStore.DEFAULT_COLORS.first()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun requestSave() {
        val size = canvasSize
        if (size.width <= 0 || size.height <= 0 || strokes.isEmpty()) return
        isProcessing = true
        coroutineScope.launch {
            val corrected = withContext(Dispatchers.Default) {
                val input = strokes.map { s ->
                    SignatureCorrection.CorrectedStroke(
                        points = s.points.map { SignatureCorrection.TimedPoint(it.x, it.y, it.tMs) },
                        colorArgb = s.color
                    )
                }
                SignatureCorrection.render(input, size.width, size.height)
            }
            isProcessing = false
            if (corrected != null) onSave(corrected)
        }
    }

    BackHandler(enabled = !showDiscardConfirm) { if (strokes.isEmpty()) onCancel() else showDiscardConfirm = true }
    run {
        // Full-screen layer in the Activity window (see SignatureStudioScreen for why this is not a Dialog).
        Surface(modifier = Modifier.fillMaxSize().zIndex(30f), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (strokes.isEmpty()) onCancel() else showDiscardConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Cancel", "إلغاء"))
                    }
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty() && !isProcessing) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(t("Undo", "تراجع"))
                    }
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty() && !isProcessing) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(t("Clear", "مسح"))
                    }
                }
                Text(
                    t("Sign with your finger — use the full area below", "وقّع بإصبعك — استخدم كامل المساحة أدناه"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .onGloballyPositioned { canvasSize = IntSize(it.size.width, it.size.height) }
                        .pointerInput(selectedColor, isProcessing) {
                            if (isProcessing) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    currentPoints.clear()
                                    currentPoints.add(StrokePt(it.x, it.y))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentPoints.add(StrokePt(change.position.x, change.position.y))
                                },
                                onDragEnd = {
                                    if (currentPoints.size > 1) strokes.add(DrawnStroke(currentPoints.toList(), selectedColor))
                                    currentPoints.clear()
                                }
                            )
                        }
                ) {
                    // Live preview only: a simple, zero-latency raw polyline while actively drawing.
                    // The actual saved bitmap is produced by SignatureCorrection.render() in requestSave(),
                    // which applies denoise + spline smoothing + pressure-simulated variable width — the
                    // preview here is intentionally NOT what gets saved.
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(
                            color = Color(0xFFE0E0E0),
                            start = Offset(size.width * 0.08f, size.height * 0.62f),
                            end = Offset(size.width * 0.92f, size.height * 0.62f),
                            strokeWidth = 2f
                        )
                        fun strokeOf(pts: List<StrokePt>, color: Int) {
                            if (pts.size < 2) return
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color(color), style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                        strokes.forEach { strokeOf(it.points, it.color) }
                        strokeOf(currentPoints, selectedColor)
                    }
                    if (strokes.isEmpty() && currentPoints.isEmpty() && !isProcessing) {
                        Text(
                            t("Sign here", "وقّع هنا"),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    if (isProcessing) {
                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = GoldBase, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(t("Refining your signature…", "جاري تحسين التوقيع…"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        SignatureStore.DEFAULT_COLORS.forEach { c ->
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (selectedColor == c) 2.dp else 1.dp,
                                        color = if (selectedColor == c) GoldBase else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                                    .clickable(enabled = !isProcessing) { selectedColor = c }
                            )
                        }
                    }
                    Button(
                        onClick = { requestSave() },
                        enabled = strokes.isNotEmpty() && !isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(t("Save", "حفظ"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(t("Discard signature?", "تجاهل التوقيع؟")) },
            text = { Text(t("Your drawing has not been saved yet.", "لم يتم حفظ الرسم بعد.")) },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onCancel() }) {
                    Text(t("Discard", "تجاهل"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text(t("Keep editing", "متابعة الرسم")) } }
        )
    }
}
