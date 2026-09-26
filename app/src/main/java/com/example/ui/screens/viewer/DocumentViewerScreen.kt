package com.example.ui.screens.viewer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.data.model.PageEntity
import com.example.data.model.PageSizePreset
import com.example.engine.cv.ImageProcessor
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.components.MergePagesDialog
import com.example.ui.components.PageActionsBottomSheet
import com.example.ui.components.ScanActionButton
import com.example.ui.screens.viewer.components.SelectionActionBar
import com.example.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    val isArabic = context.resources.configuration.locales[0].language == "ar"

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

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    val performHaptic = {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    var isGridView by remember { mutableStateOf(true) } // Images inside documents are always grid by default
    var showPdfExportDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Handle Back Press: selection mode -> grid view -> home screen
    BackHandler {
        if (selectionMode) {
            selectionMode = false
            selectedPageIds = emptySet()
        } else if (!isGridView) {
            isGridView = true
        } else {
            onNavigateBack()
        }
    }

    // Page Actions Bottom Sheet and Merge Dialog states
    var pageForActions by remember { mutableStateOf<PageEntity?>(null) }
    var pageForActionsIndex by remember { mutableStateOf(0) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var initialMergePageIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var targetPageForReplace by remember { mutableStateOf<PageEntity?>(null) }

    // Photo picker for adding pages from gallery
    val addPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val newPages = uris.map { uri ->
                    async { // Scope already provided by coroutineScope.launch
                        withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openInputStream(uri)
                            val bmp = BitmapFactory.decodeStream(stream)
                            stream?.close()
                            if (bmp != null) {
                                val raw = ImageProcessor.saveBitmapToFile(context, bmp, "add_raw_")
                                val proc = ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                                val procPath = ImageProcessor.saveBitmapToFile(context, proc, "add_proc_")
                                if (bmp != proc) bmp.recycle()
                                proc.recycle()
                                Pair(raw, procPath)
                            } else null
                        }
                    }
                }.awaitAll().filterNotNull()
                
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
            val pageToReplace = targetPageForReplace ?: pages.getOrNull(pagerState.currentPage) ?: return@rememberLauncherForActivityResult
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
                    viewModel.replacePage(pageToReplace.id, raw, procPath)
                    targetPageForReplace = null
                    Toast.makeText(context, if (isArabic) "تم استبدال الصفحة بنجاح" else "Page replaced", Toast.LENGTH_SHORT).show()
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
                            text = if (isArabic) "${selectedPageIds.size} محدد" else "${selectedPageIds.size} Selected",
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
                                    text = if (isGridView) {
                                        if (isArabic) "${pages.size} صور / صفحات" else "${pages.size} pages"
                                    } else {
                                        stringResource(R.string.txt_page_of, pagerState.currentPage + 1, pages.size)
                                    },
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
                    } else if (!isGridView) {
                        IconButton(onClick = { isGridView = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isArabic) "العودة للشبكة" else "Back to Grid")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        // Toggle Select All / Deselect All
                        IconButton(onClick = {
                            selectedPageIds = if (selectedPageIds.size == pages.size) {
                                emptySet()
                            } else {
                                pages.map { it.id }.toSet()
                            }
                            if (selectedPageIds.isEmpty()) selectionMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = if (isArabic) "تحديد الكل" else "Select All",
                                tint = if (selectedPageIds.size == pages.size) Emerald400 else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Merge Selected into Single Page
                        if (selectedPageIds.isNotEmpty()) {
                            IconButton(onClick = {
                                initialMergePageIds = selectedPageIds.toList()
                                showMergeDialog = true
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MergeType,
                                    contentDescription = if (isArabic) "دمج الصور المحددة في صفحة واحدة" else "Merge Selected into Single Page",
                                    tint = Emerald400
                                )
                            }
                        }

                        // Share Selected
                        if (selectedPageIds.isNotEmpty()) {
                            IconButton(onClick = {
                                val selectedFiles = pages.filter { selectedPageIds.contains(it.id) }.map { File(it.processedImagePath) }
                                if (selectedFiles.size == 1) {
                                    shareFile(context, selectedFiles.first())
                                } else if (selectedFiles.isNotEmpty()) {
                                    shareMultipleFiles(context, selectedFiles)
                                }
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = if (isArabic) "مشاركة الصور المحددة" else "Share Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Delete Selected
                        if (selectedPageIds.isNotEmpty()) {
                            IconButton(onClick = {
                                showDeleteSelectedConfirmDialog = true
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = if (isArabic) "حذف الصور المحددة" else "Delete Selected",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else {
                        // Grid / Reader View Switcher
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.ViewCarousel else Icons.Default.GridView,
                                contentDescription = if (isGridView) "View Full Pages" else "View Grid",
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
                                // Merge Pages into Single Page
                                if (pages.size > 1) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(if (isArabic) "دمج الصور في صفحة واحدة" else "Merge into Single Page", fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, null, tint = Emerald400) },
                                        onClick = {
                                            showOverflowMenu = false
                                            val active = pages.getOrNull(pagerState.currentPage)
                                            initialMergePageIds = if (active != null) listOf(active.id) else emptyList()
                                            showMergeDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                }

                                // Multi-select mode
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "تحديد متعدد" else "Select Multiple") },
                                    leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        selectionMode = true
                                        val active = pages.getOrNull(pagerState.currentPage)
                                        if (active != null) selectedPageIds = setOf(active.id)
                                    }
                                )

                                // Print / Save as PDF (Android Print Framework)
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "طباعة / حفظ كـ PDF (نظام أندرويد)" else "Print / Save as PDF (Android)") },
                                    leadingIcon = { Icon(Icons.Default.Print, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val docTitle = doc?.title ?: "Document"
                                        PdfEngine.printScannedDocuments(context, docTitle, pages.map { it.processedImagePath })
                                    }
                                )

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
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        },
        floatingActionButton = {
            if (!selectionMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Import Button
                    ScanActionButton(
                        onClick = { addPhotoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        icon = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Import Pages",
                        modifier = Modifier.padding(bottom = if (isGridView) 16.dp else 8.dp)
                    )
                    // Camera Button
                    ScanActionButton(
                        onClick = { onNavigateToScan(docId, 0L) },
                        icon = Icons.Default.AddAPhoto,
                        contentDescription = stringResource(R.string.desc_add_page),
                        modifier = Modifier.padding(bottom = if (isGridView) 16.dp else 8.dp)
                    )
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                SelectionActionBar(
                    selectedPageIds = selectedPageIds,
                    isArabic = isArabic,
                    onMerge = {
                        initialMergePageIds = selectedPageIds.toList()
                        showMergeDialog = true
                    },
                    onShare = {
                        val selectedFiles = pages.filter { selectedPageIds.contains(it.id) }.map { File(it.processedImagePath) }
                        if (selectedFiles.size == 1) {
                            shareFile(context, selectedFiles.first())
                        } else if (selectedFiles.isNotEmpty()) {
                            shareMultipleFiles(context, selectedFiles)
                        }
                    },
                    onExportPdf = { showPdfExportDialog = true },
                    onPrint = {
                        val docTitle = doc?.title ?: "Document"
                        val paths = selectedPageIds.mapNotNull { id -> pages.find { it.id == id }?.processedImagePath }
                        PdfEngine.printScannedDocuments(context, docTitle, paths)
                    },
                    onDuplicate = {
                        selectedPageIds.forEach { viewModel.duplicatePage(it) }
                        selectionMode = false
                        selectedPageIds = emptySet()
                    },
                    onDelete = { showDeleteSelectedConfirmDialog = true }
                )
            } else if (!isGridView) {
                // Fixed 5-item bottom bar, Obsidian Ink style
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
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
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp, 62.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(
                                                width = if (isCurrentPage) 2.dp else 0.5.dp,
                                                color = if (isCurrentPage) GoldBase else MaterialTheme.colorScheme.outline,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                            }
                                    ) {
                                        AsyncImage(
                                            model = File(p.processedImagePath),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        // Page Number
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .background(Color.Black.copy(alpha = 0.6f))
                                                .padding(horizontal = 3.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall
                                            )
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
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            val activePage = pages.getOrNull(pagerState.currentPage)
                            
                            // Filters
                            IconButton(onClick = { showFilterSheet = true }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.ColorLens, contentDescription = null, tint = GoldBase)
                                    Text(stringResource(R.string.txt_filter), style = MaterialTheme.typography.labelSmall, color = GoldBase)
                                }
                            }

                            // Crop / Document Editor
                            IconButton(onClick = {
                                if (activePage != null) {
                                    onNavigateToCrop(docId, activePage.id)
                                }
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Crop, contentDescription = "Edit & Crop", tint = GoldBase)
                                    Text("Edit", style = MaterialTheme.typography.labelSmall, color = GoldBase)
                                }
                            }

                            // OCR & AI Text
                            IconButton(onClick = {
                                if (activePage != null) {
                                    onNavigateToOcr(docId, activePage.id)
                                }
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.desc_ocr), tint = GoldBase)
                                    Text(stringResource(R.string.txt_ocr), style = MaterialTheme.typography.labelSmall, color = GoldBase)
                                }
                            }

                            // Annotate & Sign
                            IconButton(onClick = {
                                if (activePage != null) {
                                    onNavigateToAnnotate(docId, activePage.id)
                                }
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.desc_sign___annotate), tint = GoldBase)
                                    Text(stringResource(R.string.txt_sign), style = MaterialTheme.typography.labelSmall, color = GoldBase)
                                }
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
                            val isPageSelected = selectedPageIds.contains(page.id)
                            Card(
                                modifier = Modifier
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .pointerInput(page.id, selectionMode, isPageSelected) {
                                        detectTapGestures(
                                            onTap = {
                                                if (selectionMode) {
                                                    selectedPageIds = if (isPageSelected) selectedPageIds - page.id else selectedPageIds + page.id
                                                    if (selectedPageIds.isEmpty()) {
                                                        selectionMode = false
                                                    }
                                                } else {
                                                    // Single tap opens image in full screen with editing tools active!
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(index)
                                                        viewModel.selectPageIndex(index)
                                                        isGridView = false
                                                    }
                                                }
                                            },
                                            onLongPress = {
                                                // Long press selects the image and activates merge, share, and other options!
                                                performHaptic()
                                                selectionMode = true
                                                selectedPageIds = if (isPageSelected) selectedPageIds - page.id else selectedPageIds + page.id
                                                if (selectedPageIds.isEmpty()) {
                                                    selectionMode = false
                                                }
                                            }
                                        )
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(
                                    width = if (isPageSelected) 3.dp else 1.dp,
                                    color = if (isPageSelected) Emerald400 else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (isPageSelected) 6.dp else 2.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = File(page.processedImagePath),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (selectionMode) {
                                        Box(
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .size(24.dp)
                                                .align(Alignment.TopEnd)
                                                .clip(CircleShape)
                                                .background(if (isPageSelected) Emerald400 else Color.Black.copy(alpha = 0.5f))
                                                .border(1.5.dp, Color.White, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isPageSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
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
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .pointerInput(pageItem.id) {
                                detectTapGestures(
                                    onLongPress = {
                                        performHaptic()
                                        selectionMode = true
                                        selectedPageIds = setOf(pageItem.id)
                                        isGridView = true
                                    }
                                )
                            },
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

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        val currentPageNumber = pagerState.currentPage + 1
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(if (isArabic) "حذف الصفحة" else "Delete Page", fontWeight = FontWeight.Bold) },
            text = { Text(if (isArabic) "هل أنت متأكد من حذف الصفحة $currentPageNumber من ${pages.size}؟" else "Are you sure you want to delete Page $currentPageNumber of ${pages.size}?") },
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
                    Text(if (isArabic) "حذف" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    // Delete Selected Confirmation Dialog
    if (showDeleteSelectedConfirmDialog) {
        val count = selectedPageIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(if (isArabic) "حذف الصور المحددة" else "Delete Selected Images", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isArabic)
                        "هل أنت متأكد من حذف $count من الصور المحددة؟ لا يمكن التراجع عن هذا الإجراء."
                    else
                        "Are you sure you want to delete $count selected image(s)? This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idsToDelete = selectedPageIds.toList()
                        showDeleteSelectedConfirmDialog = false
                        selectionMode = false
                        selectedPageIds = emptySet()
                        idsToDelete.forEach { viewModel.deletePageById(it) }
                        if (pages.size <= idsToDelete.size) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isArabic) "حذف" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirmDialog = false }) {
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

    // PDF Export & Share Dialog with Compression, Print Framework & Quality Controls
    if (showPdfExportDialog) {
        val stateVal by viewModel.uiState.collectAsState()
        var selectedSize by remember { mutableStateOf(stateVal.defaultPdfPageSize) }
        var selectedCompression by remember { mutableStateOf(stateVal.defaultPdfCompression) }
        var includeOcr by remember { mutableStateOf(true) }
        var includePageNumbers by remember { mutableStateOf(true) }
        var selectedWatermark by remember { mutableStateOf<String?>(null) }

        val targetPages = if (selectionMode) {
            pages.filter { selectedPageIds.contains(it.id) }
        } else {
            pages
        }
        val pageCount = targetPages.size
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.txt_export_document_as_pdf),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
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
                                text = if (isArabic) "الحجم التقريبي: ~$formattedSize ($pageCount صفحات)" else "Estimated size: ~$formattedSize ($pageCount pages)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Android Print Framework Action Banner (Save as PDF / Print)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPdfExportDialog = false
                                val docTitle = doc?.title ?: "Document"
                                PdfEngine.printScannedDocuments(context, docTitle, targetPages.map { it.processedImagePath })
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Print,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isArabic) "طباعة / حفظ كـ PDF عبر أندرويد" else "Print / Save as PDF (Android)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (isArabic) "استخدام إطار الطباعة لنظام أندرويد (معاينة وحفظ)" else "Use Android Print Framework (Preview & Save)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
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
                                CompressionPreset.LOW -> if (isArabic) "صغير (~120KB)" else "Small (~120KB/p)"
                                CompressionPreset.MEDIUM -> if (isArabic) "متوسط" else "Medium"
                                CompressionPreset.HIGH -> if (isArabic) "عالي (طباعة)" else "High (Print)"
                                CompressionPreset.MAXIMUM -> if (isArabic) "أقصى دقة" else "Maximum"
                            }
                            FilterChip(
                                selected = comp == selectedCompression,
                                onClick = { selectedCompression = comp },
                                shape = RoundedCornerShape(50),
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Watermark selector
                    Text(if (isArabic) "العلامة المائية (اختياري)" else "Watermark (Optional)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val watermarks = listOf(
                            null to (if (isArabic) "بدون" else "None"),
                            "CONFIDENTIAL" to (if (isArabic) "سري" else "Confidential"),
                            "APPROVED" to (if (isArabic) "مكتمل" else "Approved"),
                            "DRAFT" to (if (isArabic) "مسودة" else "Draft")
                        )
                        watermarks.forEach { (wm, label) ->
                            FilterChip(
                                selected = selectedWatermark == wm,
                                onClick = { selectedWatermark = wm },
                                shape = RoundedCornerShape(50),
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Toggles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isArabic) "ترقيم الصفحات في الأسفل" else "Include Page Numbers", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = includePageNumbers,
                            onCheckedChange = { includePageNumbers = it }
                        )
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
                            includeSearchableText = includeOcr,
                            includePageNumbers = includePageNumbers,
                            watermarkText = selectedWatermark
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

    // Page Actions Bottom Sheet (Triggered on Page Long-Press)
    pageForActions?.let { targetPage ->
        PageActionsBottomSheet(
            page = targetPage,
            pageIndex = pageForActionsIndex,
            totalPages = pages.size,
            onDismiss = { pageForActions = null },
            onMergeClick = {
                pageForActions = null
                initialMergePageIds = listOf(targetPage.id)
                showMergeDialog = true
            },
            onEditCropClick = {
                pageForActions = null
                onNavigateToCrop(docId, targetPage.id)
            },
            onOcrClick = {
                pageForActions = null
                onNavigateToOcr(docId, targetPage.id)
            },
            onAnnotateClick = {
                pageForActions = null
                onNavigateToAnnotate(docId, targetPage.id)
            },
            onRotateClick = {
                pageForActions = null
                viewModel.rotatePage(targetPage.id)
            },
            onDuplicateClick = {
                pageForActions = null
                viewModel.duplicatePage(targetPage.id)
            },
            onReplaceClick = {
                pageForActions = null
                targetPageForReplace = targetPage
                replacePhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPrintClick = {
                pageForActions = null
                viewModel.printActivePage(context)
            },
            onShareClick = {
                pageForActions = null
                shareFile(context, File(targetPage.processedImagePath))
            },
            onExportDocxClick = {
                pageForActions = null
                exportToDocx(context, targetPage)
            },
            onExportTxtClick = {
                pageForActions = null
                exportToTxt(context, targetPage)
            },
            onExportPngClick = {
                pageForActions = null
                exportToPng(context, targetPage)
            },
            onDeleteClick = {
                pageForActions = null
                viewModel.deletePageById(targetPage.id)
            }
        )
    }

    // Multi-Image Grid Merge Dialog (دمج الصور في صفحة واحدة)
    if (showMergeDialog) {
        MergePagesDialog(
            allPages = pages,
            initialSelectedPageIds = initialMergePageIds,
            onDismiss = { showMergeDialog = false },
            onMergeCompleted = { mergedPath, selectedIds, replaceSelected ->
                viewModel.mergePagesIntoSinglePage(
                    selectedPageIds = selectedIds,
                    mergedImagePath = mergedPath,
                    replaceSelected = replaceSelected
                ) {
                    showMergeDialog = false
                    selectionMode = false
                    selectedPageIds = emptySet()
                    Toast.makeText(
                        context,
                        if (isArabic) "تم دمج الصور في صفحة واحدة بنجاح" else "Images merged into single page successfully",
                        Toast.LENGTH_SHORT
                    ).show()
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

private fun exportToTxt(context: Context, page: PageEntity) {
    if (page.ocrText.isBlank()) {
        Toast.makeText(context, "No OCR text available", Toast.LENGTH_SHORT).show()
        return
    }
    val file = File(context.cacheDir, "page_${page.id}.txt")
    file.writeText(page.ocrText)
    shareFile(context, file, "text/plain")
}

private fun exportToPng(context: Context, page: PageEntity) {
    val file = File(page.processedImagePath)
    if (file.exists()) {
        shareFile(context, file, "image/png")
    }
}

private fun exportToDocx(context: Context, page: PageEntity) {
    val htmlContent = "<html><body>${page.ocrText.replace("\n", "<br>")}</body></html>"
    val file = File(context.cacheDir, "page_${page.id}.doc")
    file.writeText(htmlContent)
    shareFile(context, file, "application/msword")
}

