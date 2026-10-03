
package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.pdf.PdfEngine
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerOverlay(
    pdfFile: File,
    onDismiss: () -> Unit,
    onShareComplete: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onDismiss() }

    var totalPages by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    val thumbnailBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }

    val formattedFileSize = remember(pdfFile) {
        val bytes = pdfFile.length()
        if (bytes < 1024 * 1024) {
            "${bytes / 1024} KB"
        } else {
            String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024f * 1024f))
        }
    }

    val displayMetrics = context.resources.displayMetrics
    val targetWidth = displayMetrics.widthPixels.coerceIn(720, 1440)

    var safeSession by remember { mutableStateOf<SafePdfSession?>(null) }

    // Initialize session and open PDF safely
    DisposableEffect(pdfFile) {
        val session = try {
            if (pdfFile.exists() && pdfFile.length() > 0L) {
                SafePdfSession(pdfFile)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (session != null) {
            safeSession = session
            totalPages = session.pageCount
            if (session.pageCount == 0) {
                loadError = "PDF contains 0 pages"
            }
        } else {
            loadError = "Failed to open PDF or file is empty"
        }

        onDispose {
            safeSession?.close()
            safeSession = null
            pageBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            pageBitmaps.clear()
            thumbnailBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            thumbnailBitmaps.clear()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = InkBase
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(InkSurface1)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.txt_cancel),
                            tint = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pdfFile.nameWithoutExtension.ifBlank { stringResource(R.string.pdf_preview_title) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = formattedFileSize,
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldLight
                            )
                            if (totalPages > 0) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = stringResource(R.string.pdf_preview_page_indicator, 1, totalPages),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    if (onEditClick != null) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(GoldBase.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = stringResource(R.string.pdf_open_edit_title),
                                tint = GoldBase
                            )
                        }
                    }

                    // Open external button
                    IconButton(
                        onClick = { PdfEngine.openPdf(context, pdfFile) }
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.pdf_preview_action_open_external),
                            tint = TextSecondary
                        )
                    }
                }

                // Error View
                if (loadError != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = loadError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = InkBase)
                            ) {
                                Text(stringResource(R.string.txt_cancel))
                            }
                        }
                    }
                } else if (totalPages == 0) {
                    // Initial Loading View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GoldBase, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.pdf_preview_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    // Main PDF Pager Viewport
                    val pagerState = rememberPagerState(pageCount = { totalPages })
                    val thumbListState = rememberLazyListState()

                    // Sync thumbnail list with active page
                    LaunchedEffect(pagerState.currentPage) {
                        thumbListState.animateScrollToItem(pagerState.currentPage)
                    }

                    // Lazy on-demand rendering of current page and immediate neighbors
                    LaunchedEffect(pagerState.currentPage, safeSession) {
                        val session = safeSession ?: return@LaunchedEffect
                        val pagesToKeep = setOf(pagerState.currentPage - 1, pagerState.currentPage, pagerState.currentPage + 1)
                            .filter { it in 0 until totalPages }

                        // Evict old bitmaps outside sliding window to save RAM
                        val toEvict = pageBitmaps.keys.filter { it !in pagesToKeep }
                        toEvict.forEach { idx ->
                            val bmp = pageBitmaps.remove(idx)
                            if (bmp != null && !bmp.isRecycled) bmp.recycle()
                        }

                        // Render needed pages (active page first)
                        val neededOrder = listOf(pagerState.currentPage) + pagesToKeep.filter { it != pagerState.currentPage }
                        for (idx in neededOrder) {
                            if (!pageBitmaps.containsKey(idx)) {
                                withContext(Dispatchers.IO) {
                                    val bmp = session.renderPage(idx, targetWidth)
                                    if (bmp != null) {
                                        withContext(Dispatchers.Main) {
                                            pageBitmaps[idx] = bmp
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Background lightweight thumbnail generation
                    LaunchedEffect(safeSession, totalPages) {
                        val session = safeSession ?: return@LaunchedEffect
                        withContext(Dispatchers.IO) {
                            for (i in 0 until totalPages) {
                                if (!thumbnailBitmaps.containsKey(i)) {
                                    val thumb = session.renderThumbnail(i, 110)
                                    if (thumb != null) {
                                        withContext(Dispatchers.Main) {
                                            thumbnailBitmaps[i] = thumb
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(InkBase)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 16.dp,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                        ) { pageIndex ->
                            var scale by remember { mutableFloatStateOf(1f) }
                            var offset by remember { mutableStateOf(Offset.Zero) }

                            // Reset zoom when leaving page
                            LaunchedEffect(pagerState.currentPage) {
                                if (pagerState.currentPage != pageIndex) {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(pageIndex) {
                                        detectTapGestures(
                                            onDoubleTap = { tapOffset ->
                                                scale = if (scale > 1.2f) 1f else 2.5f
                                                offset = Offset.Zero
                                            }
                                        )
                                    }
                                    .pointerInput(pageIndex) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                                            scale = newScale
                                            if (newScale > 1f) {
                                                val maxPan = 600f * (newScale - 1f)
                                                offset = Offset(
                                                    x = (offset.x + pan.x).coerceIn(-maxPan, maxPan),
                                                    y = (offset.y + pan.y).coerceIn(-maxPan, maxPan)
                                                )
                                            } else {
                                                offset = Offset.Zero
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val bitmap = pageBitmaps[pageIndex]

                                if (bitmap != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        shadowElevation = 8.dp,
                                        color = Color.White,
                                        modifier = Modifier
                                            .graphicsLayer(
                                                scaleX = scale,
                                                scaleY = scale,
                                                translationX = offset.x,
                                                translationY = offset.y
                                            )
                                            .fillMaxWidth()
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "PDF Page ${pageIndex + 1}",
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    // Placeholder while page is rendering
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.7f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(InkSurface2),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = GoldBase,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Page Floating Pill Indicator
                        Surface(
                            shape = CircleShape,
                            color = InkSurface2.copy(alpha = 0.88f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkBorder),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / $totalPages",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = GoldLight,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Thumbnail Strip (when document has multiple pages)
                    if (totalPages > 1) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = InkSurface1
                        ) {
                            LazyRow(
                                state = thumbListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                itemsIndexed((0 until totalPages).toList()) { index, _ ->
                                    val isSelected = pagerState.currentPage == index
                                    val thumb = thumbnailBitmaps[index]

                                    Box(
                                        modifier = Modifier
                                            .width(46.dp)
                                            .height(64.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) GoldBase.copy(alpha = 0.2f) else InkSurface2)
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) GoldBase else InkBorder,
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(index)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = "Thumb ${index + 1}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Bar for Sharing via FileProvider & Saving
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = InkSurface2,
                    tonalElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.pdf_preview_share_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Primary Action: Share PDF via FileProvider
                            Button(
                                onClick = {
                                    PdfEngine.sharePdf(context, pdfFile)
                                    onShareComplete?.invoke()
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GoldBase,
                                    contentColor = InkBase
                                ),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.pdf_preview_action_share),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            // Save on Device
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        // Public Downloads/MS Scanner first (visible in Files); legacy app folder only on Android 7-9.
                                        val publicUri = withContext(Dispatchers.IO) {
                                            PdfEngine.savePdfToDownloads(context, pdfFile, pdfFile.nameWithoutExtension)
                                        }
                                        val where: String? = if (publicUri != null) "Downloads/MS Scanner" else withContext(Dispatchers.IO) {
                                            PdfEngine.savePdfToStorage(context, pdfFile, pdfFile.nameWithoutExtension).let { (u, p) -> if (u != null) (p ?: "Documents") else null }
                                        }
                                        if (where != null) {
                                            Toast.makeText(context, context.getString(R.string.export_pdf_success_saved, where), Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.export_pdf_error, "Save failed"), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, InkBorderStrong),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = InkSurface3,
                                    contentColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    tint = GoldLight,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.pdf_preview_action_save),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }

                            // Edit in Studio Button (if callback provided)
                            if (onEditClick != null) {
                                IconButton(
                                    onClick = onEditClick,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(GoldBase.copy(alpha = 0.18f))
                                        .border(1.dp, GoldBase.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                ) {
                                    Icon(
                                        Icons.Default.AutoFixHigh,
                                        contentDescription = stringResource(R.string.pdf_open_edit_title),
                                        tint = GoldBase,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Print Button
                            IconButton(
                                onClick = {
                                    PdfEngine.printPdfFile(context, pdfFile, pdfFile.nameWithoutExtension)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(InkSurface3)
                                    .border(1.dp, InkBorderStrong, RoundedCornerShape(14.dp))
                            ) {
                                Icon(
                                    Icons.Default.Print,
                                    contentDescription = stringResource(R.string.pdf_preview_action_print),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Thread-safe wrapper around Android PdfRenderer that allows on-demand page
 * and thumbnail rendering without loading all pages into memory at once.
 */
class SafePdfSession(val file: File) {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    var pageCount: Int = 0
        private set
    private val lock = Any()

    init {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd!!)
        pageCount = renderer!!.pageCount
    }

    fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        if (pageIndex !in 0 until pageCount) return null
        try {
            val page = r.openPage(pageIndex)
            val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
            val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(100)
            val bmp = try {
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                Bitmap.createBitmap(targetWidth / 2, targetHeight / 2, Bitmap.Config.RGB_565)
            }
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun renderThumbnail(pageIndex: Int, thumbWidth: Int = 110): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        if (pageIndex !in 0 until pageCount) return null
        try {
            val page = r.openPage(pageIndex)
            val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
            val thumbHeight = (thumbWidth * aspect).toInt().coerceAtLeast(140)
            val bmp = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() = synchronized(lock) {
        try { renderer?.close() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
        renderer = null
        pfd = null
    }
}




