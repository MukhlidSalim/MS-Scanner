package com.example.ui.screens.settings

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.SignatureEntity
import com.example.data.repository.DocumentRepository
import com.example.engine.annotation.SignatureStore
import com.example.ui.theme.Emerald400
import com.example.ui.theme.GoldBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StrokePt(val x: Float, val y: Float)
private data class DrawnStroke(val points: List<StrokePt>, val color: Int)

/**
 * Reusable signature vault (Settings -> Signatures). Unchanged from the previous version except that
 * "New signature" / "Redraw" now open [FullScreenSignatureWorkspace] instead of a small AlertDialog box
 * (see that composable for the root-cause fix: a small fixed-height box made natural handwriting
 * impossible; drawing now has the full screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var pendingDelete by remember { mutableStateOf<SignatureEntity?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(t("My Signatures", "توقيعاتي"), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { replacing = null; showWorkspace = true },
                        containerColor = GoldBase,
                        contentColor = Color.Black,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(t("New signature", "توقيع جديد"), fontWeight = FontWeight.Bold) }
                    )
                }
            ) { padding ->
                if (signatures.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Draw, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            t(
                                "Create your signature once, then reuse it instantly on any document.",
                                "أنشئ توقيعك مرة واحدة، ثم استخدمه فورًا على أي مستند."
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(padding)
                    ) {
                        items(signatures, key = { it.id }) { sig ->
                            SignatureCard(
                                signature = sig,
                                isArabic = isArabic,
                                onReplace = { replacing = sig; showWorkspace = true },
                                onDelete = { pendingDelete = sig }
                            )
                        }
                    }
                }
            }
        }
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
                        repository.deleteSignature(old.id)
                        SignatureStore.delete(old.imagePath)
                    }
                    repository.saveSignature(t("Signature", "توقيع"), path)
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
    isArabic: Boolean,
    onReplace: () -> Unit,
    onDelete: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var bitmap by remember(signature.imagePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(signature.imagePath) {
        bitmap = withContext(Dispatchers.IO) { SignatureStore.loadDisplayable(signature.imagePath, 600) }
    }
    var showMenu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    // Neutral (non-white) card so a REAL transparent signature is visibly obvious here,
                    // unlike the white-box bug this feature fixes.
                    .background(Color(0xFFEDEDF2)),
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
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(t("Redraw / Replace", "إعادة رسم / استبدال")) },
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
            Text(
                signature.title,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * ROOT CAUSE fixed: the previous signing workspace was a fixed ~170dp-tall box inside an AlertDialog —
 * a small, cramped rectangle in which a natural signature is physically impossible to draw (not enough
 * room for the hand motion of a real signature). This replaces it with a dedicated FULL-SCREEN Dialog
 * (same "overlay screen" pattern as PdfViewerOverlay / SignatureStudioScreen): the entire screen, minus a
 * compact top bar and a slim colour/action bar, is the drawing surface. The canvas keeps a true
 * transparent background (no box, no card) — only strokes are stored.
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

    fun buildBitmap(): Bitmap? {
        val size = canvasSize
        if (size.width <= 0 || size.height <= 0 || strokes.isEmpty()) return null
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888) // transparent by default
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        for (s in strokes) {
            if (s.points.size < 2) continue
            paint.color = s.color
            val path = android.graphics.Path()
            path.moveTo(s.points[0].x, s.points[0].y)
            for (p in s.points.drop(1)) path.lineTo(p.x, p.y)
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    Dialog(
        onDismissRequest = { if (strokes.isEmpty()) onCancel() else showDiscardConfirm = true },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F4F7)) {
            Column(Modifier.fillMaxSize()) {
                // Compact top bar: title + cancel + clear, leaving maximum room to draw.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (strokes.isEmpty()) onCancel() else showDiscardConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Cancel", "إلغاء"))
                    }
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(t("Undo", "تراجع"))
                    }
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(t("Clear", "مسح"))
                    }
                }
                Text(
                    t("Sign with your finger — use the full area below", "وقّع بإصبعك — استخدم كامل المساحة أدناه"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, bottom = 4.dp)
                )
                // THE drawing surface: fills essentially the whole remaining screen (full-screen workspace).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .onGloballyPositioned { canvasSize = IntSize(it.size.width, it.size.height) }
                        .pointerInput(selectedColor) {
                            detectDragGestures(
                                onDragStart = { currentPoints.clear(); currentPoints.add(StrokePt(it.x, it.y)) },
                                onDrag = { change, _ -> change.consume(); currentPoints.add(StrokePt(change.position.x, change.position.y)) },
                                onDragEnd = {
                                    if (currentPoints.size > 1) strokes.add(DrawnStroke(currentPoints.toList(), selectedColor))
                                    currentPoints.clear()
                                }
                            )
                        }
                ) {
                    // A faint baseline guide (visual aid only, never part of the saved bitmap).
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
                            drawPath(path, Color(color), style = Stroke(width = 9f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                        strokes.forEach { strokeOf(it.points, it.color) }
                        strokeOf(currentPoints, selectedColor)
                    }
                    if (strokes.isEmpty() && currentPoints.isEmpty()) {
                        Text(
                            t("Sign here", "وقّع هنا"),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                // Colour + Save bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
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
                                    .clickable { selectedColor = c }
                            )
                        }
                    }
                    Button(
                        onClick = { buildBitmap()?.let(onSave) },
                        enabled = strokes.isNotEmpty(),
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

/** Kept for source compatibility with any older call site still referencing the dialog-based sheet. */
@Deprecated("Replaced by FullScreenSignatureWorkspace (full-screen drawing area).")
@Composable
fun DrawSignatureSheet(
    isArabic: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    FullScreenSignatureWorkspace(isArabic = isArabic, title = title, onCancel = onDismiss, onSave = onSave)
}
