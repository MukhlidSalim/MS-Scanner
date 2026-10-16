package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.engine.pdf.PdfEngine
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Read-only PDF viewer: continuous VERTICAL scrolling (like professional PDF readers), pinch / double-tap zoom.
 *
 * ROOT CAUSE of the clipped bottom buttons: the viewer was a Dialog with decorFitsSystemWindows = false, where
 * systemBarsPadding() is not reliably dispatched, and the action row used fixed-width buttons that overflowed on
 * narrow screens / Arabic labels. It is now a full-screen layer inside the edge-to-edge Activity window
 * (WindowInsets.safeDrawing is exact there) and the action bar uses equal-weight items that always fit.
 * Public signature unchanged for every caller.
 */
@Composable
fun PdfViewerOverlay(
    pdfFile: File,
    onDismiss: () -> Unit,
    onShareComplete: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    BackHandler { onDismiss() }

    var session by remember(pdfFile) { mutableStateOf<SafePdfSession?>(null) }
    var loadError by remember(pdfFile) { mutableStateOf<String?>(null) }
    val pageBitmaps = remember(pdfFile) { mutableStateMapOf<Int, Bitmap>() }
    val thumbnailBitmaps = remember(pdfFile) { mutableStateMapOf<Int, Bitmap>() }

    val formattedFileSize = remember(pdfFile) {
        val bytes = pdfFile.length()
        if (bytes < 1024 * 1024) "${bytes / 1024} KB"
        else String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024f * 1024f))
    }
    val cleanTitle = remember(pdfFile) {
        pdfFile.nameWithoutExtension.replace(Regex("^\\d{10,}_"), "").ifBlank { "Document" }
    }
    val targetWidth = context.resources.displayMetrics.widthPixels.coerceIn(720, 1440)

    // Opened off the main thread (the size of every page is read once).
    LaunchedEffect(pdfFile) {
        val opened = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { if (pdfFile.exists() && pdfFile.length() > 0L) SafePdfSession(pdfFile) else null }.getOrNull()
        }
        if (!isActive) { opened?.close(); return@LaunchedEffect }
        when {
            opened == null -> loadError = t("Failed to open the PDF or the file is empty", "تعذر فتح ملف PDF أو أن الملف فارغ")
            opened.pageCount == 0 -> { opened.close(); loadError = t("The PDF contains no pages", "ملف PDF لا يحتوي على صفحات") }
            else -> session = opened
        }
    }
    DisposableEffect(pdfFile) {
        onDispose {
            session?.close()
            session = null
            // No recycle(): a bitmap removed in this frame may still be drawn once; GC frees it safely.
            pageBitmaps.clear()
            thumbnailBitmaps.clear()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().zIndex(10f), color = InkBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val s = session
            val listState = rememberLazyListState()
            val currentPage by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val visible = info.visibleItemsInfo
                    if (visible.isEmpty()) 0 else {
                        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                        (visible.firstOrNull { it.offset <= center && it.offset + it.size >= center } ?: visible.first()).index
                    }
                }
            }

            // ---------------------------------------------------------------- top bar
            Row(
                modifier = Modifier.fillMaxWidth().background(InkSurface1).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.txt_cancel), tint = TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        cleanTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (s != null) "$formattedFileSize • " + stringResource(R.string.pdf_preview_page_indicator, currentPage + 1, s.pageCount)
                        else formattedFileSize,
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1
                    )
                }
                IconButton(onClick = { PdfEngine.openPdf(context, pdfFile) }) {
                    Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.pdf_preview_action_open_external), tint = TextSecondary)
                }
            }

            // ---------------------------------------------------------------- content
            when {
                loadError != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(loadError ?: "", color = TextPrimary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = InkBase)) {
                            Text(stringResource(R.string.txt_cancel))
                        }
                    }
                }
                s == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = GoldBase, strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.pdf_preview_loading), color = TextSecondary)
                    }
                }
                else -> {
                    // Render only the visible pages ± 3 (bounded memory for very large files).
                    LaunchedEffect(s) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                            .distinctUntilChanged()
                            .collectLatest { visible ->
                                if (visible.isEmpty()) return@collectLatest
                                val keep = ((visible.first() - 3)..(visible.last() + 3)).filter { it in 0 until s.pageCount }.toSet()
                                pageBitmaps.keys.filter { it !in keep }.forEach { pageBitmaps.remove(it) }
                                for (idx in visible + keep.filter { it !in visible }) {
                                    if (pageBitmaps.containsKey(idx)) continue
                                    val bmp = withContext(Dispatchers.IO) { s.renderPage(idx, targetWidth) } ?: continue
                                    pageBitmaps[idx] = bmp
                                }
                            }
                    }
                    LaunchedEffect(s) {
                        for (i in 0 until s.pageCount) {
                            if (thumbnailBitmaps.containsKey(i)) continue
                            val thumb = withContext(Dispatchers.IO) { s.renderThumbnail(i, 110) } ?: continue
                            thumbnailBitmaps[i] = thumb
                        }
                    }

                    var scale by remember { mutableFloatStateOf(1f) }
                    var offsetX by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .pointerInput(s) {
                                detectTapGestures(onDoubleTap = {
                                    if (scale > 1.05f) { scale = 1f; offsetX = 0f } else scale = 2.2f
                                })
                            }
                            // Zoom / zoomed pan. Initial pass: the list never scrolls while two fingers pinch.
                            // At scale 1 a single finger is NOT consumed: native vertical scroll + fling.
                            .pointerInput(s) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2 || scale > 1.01f) {
                                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                            val pan = event.calculatePan()
                                            scale = newScale
                                            val maxX = size.width * (newScale - 1f) / 2f
                                            offsetX = if (newScale > 1.01f) (offsetX + pan.x).coerceIn(-maxX, maxX) else 0f
                                            if (pan.y != 0f) listState.dispatchRawDelta(-pan.y / newScale)
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale; translationX = offsetX
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(count = s.pageCount, key = { it }) { idx ->
                                val bmp = pageBitmaps[idx]
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.White,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f / s.aspect(idx))
                                ) {
                                    if (bmp != null) {
                                        val image = remember(bmp) { bmp.asImageBitmap() }
                                        Image(image, contentDescription = "PDF ${idx + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = GoldBase, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = InkSurface2.copy(alpha = 0.88f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkBorder),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        ) {
                            Text(
                                "${currentPage + 1} / ${s.pageCount}", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = GoldLight,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (s.pageCount > 1) {
                        val thumbState = rememberLazyListState()
                        LaunchedEffect(currentPage) { thumbState.animateScrollToItem(currentPage) }
                        LazyRow(
                            state = thumbState,
                            modifier = Modifier.fillMaxWidth().background(InkSurface1).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(count = s.pageCount, key = { it }) { index ->
                                val selected = currentPage == index
                                Box(
                                    modifier = Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) GoldBase.copy(alpha = 0.2f) else InkSurface2)
                                        .border(if (selected) 2.dp else 1.dp, if (selected) GoldBase else InkBorder, RoundedCornerShape(6.dp))
                                        .clickable {
                                            scale = 1f; offsetX = 0f
                                            scope.launch { listState.animateScrollToItem(index) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val thumb = thumbnailBitmaps[index]
                                    if (thumb != null) {
                                        val image = remember(thumb) { thumb.asImageBitmap() }
                                        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- action bar (always fully visible)
            Surface(color = InkSurface2, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    ViewerAction(Icons.Default.Share, stringResource(R.string.pdf_preview_action_share), GoldBase, Modifier.weight(1f)) {
                        PdfEngine.sharePdf(context, pdfFile)
                        onShareComplete?.invoke()
                    }
                    ViewerAction(Icons.Default.Download, stringResource(R.string.pdf_preview_action_save), GoldLight, Modifier.weight(1f)) {
                        scope.launch {
                            val publicUri = withContext(Dispatchers.IO) { PdfEngine.savePdfToDownloads(context, pdfFile, cleanTitle) }
                            val where: String? = if (publicUri != null) "Downloads/MS Scanner" else withContext(Dispatchers.IO) {
                                PdfEngine.savePdfToStorage(context, pdfFile, cleanTitle).let { (u, p) -> if (u != null) (p ?: "Documents") else null }
                            }
                            Toast.makeText(
                                context,
                                if (where != null) context.getString(R.string.export_pdf_success_saved, where)
                                else context.getString(R.string.export_pdf_error, "Save failed"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    if (onEditClick != null) {
                        ViewerAction(Icons.Default.Draw, t("Sign / Edit", "توقيع / تعديل"), Emerald400, Modifier.weight(1f), onEditClick)
                    }
                    ViewerAction(Icons.Default.Print, stringResource(R.string.pdf_preview_action_print), TextSecondary, Modifier.weight(1f)) {
                        PdfEngine.printPdfFile(context, pdfFile, cleanTitle)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Thread-safe PdfRenderer wrapper: on-demand rendering, page aspect ratios read once. */
class SafePdfSession(val file: File) {
    private var pfd: ParcelFileDescriptor? = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private var renderer: PdfRenderer? = PdfRenderer(pfd!!)
    val pageCount: Int = renderer!!.pageCount
    private val lock = Any()

    /** height / width of every page (exact placeholders before rendering). */
    private val aspects = FloatArray(pageCount) { i ->
        runCatching {
            val p = renderer!!.openPage(i)
            try { p.height.toFloat() / p.width.toFloat().coerceAtLeast(1f) } finally { p.close() }
        }.getOrDefault(1.414f).coerceIn(0.2f, 5f)
    }

    fun aspect(index: Int): Float = aspects.getOrElse(index) { 1.414f }

    fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? = render(pageIndex, targetWidth, 100)

    fun renderThumbnail(pageIndex: Int, thumbWidth: Int = 110): Bitmap? = render(pageIndex, thumbWidth, 60)

    private fun render(pageIndex: Int, width: Int, minHeight: Int): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        if (pageIndex !in 0 until pageCount) return null
        try {
            val page = r.openPage(pageIndex)
            // close() in finally: an exception left the page open and every later openPage() failed.
            try {
                val height = (width * page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)).toInt().coerceAtLeast(minHeight)
                val bmp = try {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    Bitmap.createBitmap((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                }
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() = synchronized(lock) {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null
        pfd = null
    }
}
