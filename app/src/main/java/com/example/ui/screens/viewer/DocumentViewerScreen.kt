package com.example.ui.screens.viewer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.CompressionPreset
import com.example.data.model.FilterType
import com.example.data.model.PageSizePreset
import com.example.engine.cv.ImageProcessor
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.theme.CyanScan
import com.example.ui.theme.Emerald400
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.StudioCanvasBg
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.DocumentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    docId: Long,
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToScan: (Long, Long) -> Unit, // docId, replacePageId
    onNavigateToCrop: (Long, Long) -> Unit,
    onNavigateToOcr: (Long, Long) -> Unit,
    onNavigateToAnnotate: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val doc = uiState.activeDocument
    val pages = uiState.activePages

    LaunchedEffect(docId) {
        viewModel.loadDocument(docId)
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPageIds by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(pagerState.currentPage) {
        if (pages.isNotEmpty() && pagerState.currentPage in pages.indices) {
            viewModel.selectPageIndex(pagerState.currentPage)
        }
    }

    var showPdfExportDialog by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAddPageDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Photo picker for adding pages from gallery
    val addPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val newPages = mutableListOf<Pair<String, String>>()
                for (uri in uris) {
                    val stream = context.contentResolver.openInputStream(uri)
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) {
                        val raw = ImageProcessor.saveBitmapToFile(context, bmp, "add_raw_")
                        val proc = ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                        val procPath = ImageProcessor.saveBitmapToFile(context, proc, "add_proc_")
                        if (bmp != proc) bmp.recycle()
                        proc.recycle()
                        newPages.add(Pair(raw, procPath))
                    }
                }
                if (newPages.isNotEmpty()) {
                    viewModel.addPagesToCurrentDocument(newPages)
                    Toast.makeText(context, "Added ${newPages.size} pages", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Photo picker for replacing the active page
    val replacePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val activePage = pages.getOrNull(pagerState.currentPage) ?: return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val stream = context.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                if (bmp != null) {
                    val raw = ImageProcessor.saveBitmapToFile(context, bmp, "rep_raw_")
                    val proc = ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                    val procPath = ImageProcessor.saveBitmapToFile(context, proc, "rep_proc_")
                    if (bmp != proc) bmp.recycle()
                    proc.recycle()
                    viewModel.replacePage(activePage.id, raw, procPath)
                    Toast.makeText(context, "Page replaced", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(
                            text = "${selectedPageIds.size} Selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Column(
                            modifier = Modifier.clickable {
                                renameInput = doc?.title ?: ""
                                showRenameDialog = true
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = doc?.title ?: "Document",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            if (pages.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.txt_page_of, pagerState.currentPage + 1, pages.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectionMode = false; selectedPageIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.txt_cancel_selection))
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                },
                actions = {
                    // Grid / Reader View Switcher
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewCarousel else Icons.Default.GridView,
                            contentDescription = "Toggle Grid View",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // PDF Export Button
                    IconButton(
                        onClick = { showPdfExportDialog = true },
                        modifier = Modifier.testTag("export_pdf_top_btn")
                    ) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = stringResource(R.string.desc_export_pdf),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Share Button
                    IconButton(onClick = {
                        val activePage = pages.getOrNull(pagerState.currentPage) ?: return@IconButton
                        shareFile(context, File(activePage.processedImagePath))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.desc_share_page))
                    }

                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.txt_options))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            // Reorder Pages
                            if (pages.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("Reorder Pages") },
                                    leadingIcon = { Icon(Icons.Default.Reorder, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showReorderDialog = true
                                    }
                                )
                            }

                            // Replace Current Page
                            DropdownMenuItem(
                                text = { Text("Replace Page (Camera)") },
                                leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    val activePage = pages.getOrNull(pagerState.currentPage)
                                    if (activePage != null) {
                                        onNavigateToScan(docId, activePage.id)
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Replace Page (Gallery)") },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    replacePhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )

                            // Save to Gallery
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_save_to_gallery)) },
                                leadingIcon = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.saveDocumentToGallery(context, docId)
                                }
                            )

                            // Print
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_print)) },
                                leadingIcon = { Icon(Icons.Default.Print, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.printActivePage(context)
                                }
                            )

                            if (pages.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.desc_share_all_jpegs)) },
                                    leadingIcon = { Icon(Icons.Default.Collections, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val allFiles = pages.map { File(it.processedImagePath) }
                                        shareMultipleFiles(context, allFiles)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddPageDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = stringResource(R.string.desc_add_page))
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Horizontal Miniature Filmstrip (if multi-page)
                    if (pages.size > 1) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(pages) { index, p ->
                                val isCurrentPage = index == pagerState.currentPage
                                val isPageSelected = selectedPageIds.contains(p.id)
                                Box(
                                    modifier = Modifier
                                        .size(46.dp, 62.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(
                                            width = if (isCurrentPage) 2.5.dp else 1.dp,
                                            color = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            if (selectionMode) {
                                                selectedPageIds = if (isPageSelected) selectedPageIds - p.id else selectedPageIds + p.id
                                            } else {
                                                coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = File(p.processedImagePath),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Page Number Chip
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(2.dp),
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color.Black.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (selectionMode) {
                                        Box(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(16.dp)
                                                .align(Alignment.TopEnd)
                                                .clip(CircleShape)
                                                .background(if (isPageSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isPageSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Primary Document Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activePage = pages.getOrNull(pagerState.currentPage)

                        // Filters
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.testTag("action_filter_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ColorLens, contentDescription = stringResource(R.string.desc_filters), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_filter), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Crop / Document Editor
                        IconButton(onClick = {
                            if (activePage != null) {
                                onNavigateToCrop(docId, activePage.id)
                            }
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Crop, contentDescription = "Edit & Crop", tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Edit", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // OCR & AI Text
                        IconButton(
                            onClick = {
                                if (activePage != null) {
                                    onNavigateToOcr(docId, activePage.id)
                                }
                            },
                            modifier = Modifier.testTag("action_ocr_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.desc_ocr), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_ocr), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Annotate & Sign
                        IconButton(
                            onClick = {
                                if (activePage != null) {
                                    onNavigateToAnnotate(docId, activePage.id)
                                }
                            },
                            modifier = Modifier.testTag("action_annotate_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.desc_sign___annotate), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_sign), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Rotate
                        IconButton(
                            onClick = { viewModel.rotateActivePage() },
                            modifier = Modifier.testTag("action_rotate_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.desc_rotate))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_rotate), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Duplicate
                        IconButton(
                            onClick = { viewModel.duplicateActivePage() },
                            modifier = Modifier.testTag("action_duplicate_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FileCopy, contentDescription = stringResource(R.string.desc_duplicate))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_copy), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Delete
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            modifier = Modifier.testTag("action_delete_btn")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.desc_delete), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.txt_delete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(StudioCanvasBg)
        ) {
            // Quality Report Banner with Auto-Fix
            val quality = uiState.currentQualityReport
            if (quality != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (quality.isBlurry || quality.isDark) Color(0xFF451A03) else Color(0xFF064E3B),
                    border = BorderStroke(
                        0.5.dp,
                        if (quality.isBlurry || quality.isDark) WarningAmber.copy(alpha = 0.5f) else Emerald400.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (quality.isBlurry || quality.isDark) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (quality.isBlurry || quality.isDark) WarningAmber else Emerald400,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = quality.statusTextEn,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (quality.isBlurry || quality.isDark || quality.isLowContrast) {
                            TextButton(
                                onClick = { viewModel.applyFilterToActivePage(FilterType.AUTO) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Emerald400, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Auto Fix", color = Emerald400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Main Document Page View (Grid or Pager)
            if (pages.isNotEmpty()) {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        gridItemsIndexed(pages) { index, page ->
                            Card(
                                modifier = Modifier
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(index)
                                            isGridView = false
                                        }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = File(page.processedImagePath),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.Black.copy(alpha = 0.75f)
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(14.dp)
                    ) { index ->
                        val pageItem = pages[index]
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = File(pageItem.processedImagePath),
                                contentDescription = stringResource(R.string.page_n, index + 1),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
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

    // Add Page Dialog (Camera vs Gallery)
    if (showAddPageDialog) {
        AlertDialog(
            onDismissRequest = { showAddPageDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text("Add Pages to Document", fontWeight = FontWeight.Bold) },
            text = { Text("Scan new pages with the camera or choose from your gallery.") },
            confirmButton = {
                Button(
                    onClick = {
                        showAddPageDialog = false
                        onNavigateToScan(docId, 0L)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Camera")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showAddPageDialog = false
                        addPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pick Photos")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        val currentPageNumber = pagerState.currentPage + 1
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text("Delete Page", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete Page $currentPageNumber of ${pages.size}?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteActivePage()
                        if (pages.size <= 1) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    // Reorder Pages Dialog
    if (showReorderDialog) {
        ReorderPagesScreen(
            pages = pages,
            onSave = { newOrder ->
                viewModel.reorderPages(newOrder)
                showReorderDialog = false
            },
            onCancel = { showReorderDialog = false }
        )
    }

    // Rename Document Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.action_rename), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank() && doc != null) {
                            viewModel.renameDocument(doc.id, renameInput.trim())
                        }
                        showRenameDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    // Filter Selection Bottom Sheet
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.txt_document_filters),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterType.values().forEach { filter ->
                        val activeFilter = pages.getOrNull(pagerState.currentPage)?.filterType
                        val isSelected = filter.name == activeFilter

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    viewModel.applyFilterToActivePage(filter)
                                    showFilterSheet = false
                                }
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = filter.displayNameEn,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                if (pages.size > 1) {
                    Button(
                        onClick = {
                            val activeFilterStr = pages.getOrNull(pagerState.currentPage)?.filterType ?: FilterType.AUTO.name
                            val activeFilter = try { FilterType.valueOf(activeFilterStr) } catch(e: Exception) { FilterType.AUTO }
                            viewModel.applyFilterToAllPages(context, activeFilter)
                            showFilterSheet = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.txt_apply_to_all_pages), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // PDF Export & Share Dialog with Compression & Quality Controls
    if (showPdfExportDialog) {
        val stateVal by viewModel.uiState.collectAsState()
        var selectedSize by remember { mutableStateOf(stateVal.defaultPdfPageSize) }
        var selectedCompression by remember { mutableStateOf(stateVal.defaultPdfCompression) }
        var includeOcr by remember { mutableStateOf(true) }

        val pageCount = if (selectionMode) selectedPageIds.size else pages.size
        val estimatedBytes = remember(selectedCompression, pageCount) {
            PdfEngine.estimatePdfSizeBytes(pageCount, selectedCompression)
        }
        val formattedSize = remember(estimatedBytes) {
            PdfEngine.formatEstimatedSize(estimatedBytes)
        }

        AlertDialog(
            onDismissRequest = { showPdfExportDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = stringResource(R.string.txt_export_document_as_pdf),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Estimated File Size Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Estimated size: ~$formattedSize ($pageCount pages)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Text(stringResource(R.string.txt_page_format), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PageSizePreset.values().forEach { size ->
                            FilterChip(
                                selected = size == selectedSize,
                                onClick = { selectedSize = size },
                                shape = RoundedCornerShape(50),
                                label = { Text(size.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Text(stringResource(R.string.txt_quality___compression), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompressionPreset.values().forEach { comp ->
                            val label = when (comp) {
                                CompressionPreset.LOW -> "Small (~120KB/p)"
                                CompressionPreset.MEDIUM -> "Medium"
                                CompressionPreset.HIGH -> "High (Print)"
                                CompressionPreset.MAXIMUM -> "Maximum"
                            }
                            FilterChip(
                                selected = comp == selectedCompression,
                                onClick = { selectedCompression = comp },
                                shape = RoundedCornerShape(50),
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.txt_searchable_ocr_text_layer), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = includeOcr,
                            onCheckedChange = { includeOcr = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val config = PdfExportConfig(
                            title = doc?.title ?: "Document",
                            pageSize = selectedSize,
                            compression = selectedCompression,
                            includeSearchableText = includeOcr
                        )
                        viewModel.exportDocumentToPdf(config, if (selectionMode) selectedPageIds else null) { generatedPdf ->
                            showPdfExportDialog = false
                            if (selectionMode) {
                                selectionMode = false
                                selectedPageIds = emptySet()
                            }
                            shareFile(context, generatedPdf, "application/pdf")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("export_pdf_confirm_btn")
                ) {
                    if (uiState.isExportingPdf) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text(stringResource(R.string.txt_export___share), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfExportDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}

private fun shareFile(context: Context, file: File, mimeType: String = "image/jpeg") {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Document via"))
    } catch (e: Exception) {}
}

private fun shareMultipleFiles(context: Context, files: List<File>, mimeType: String = "image/jpeg") {
    try {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Pages via"))
    } catch (e: Exception) {}
}
