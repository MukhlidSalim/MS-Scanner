package com.example.ui.screens.viewer

import androidx.compose.ui.res.stringResource
import com.example.R
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.example.data.model.CompressionPreset
import com.example.data.model.FilterType
import com.example.data.model.PageSizePreset
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.DocumentViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    docId: Long,
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCrop: (Long, Long) -> Unit,
    onNavigateToOcr: (Long, Long) -> Unit,
    onNavigateToAnnotate: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    var showFilterSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    
    val activity = context as? android.app.Activity
    val coroutineScope = rememberCoroutineScope()
    val scannerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanResult = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { newPages ->
                coroutineScope.launch {
                    val processedPages = mutableListOf<Pair<String, String>>()
                    for (page in newPages) {
                        val stream = context.contentResolver.openInputStream(page.imageUri)
                        val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) {
                            val rawPath = com.example.engine.cv.ImageProcessor.saveBitmapToFile(context, bmp, "scan_raw_")
                            val proc = com.example.engine.cv.ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                            val procPath = com.example.engine.cv.ImageProcessor.saveBitmapToFile(context, proc, "scan_proc_")
                            processedPages.add(Pair(rawPath, procPath))
                            if (bmp != proc) bmp.recycle()
                            proc.recycle()
                        }
                    }
                    if (processedPages.isNotEmpty()) {
                        viewModel.addPagesToCurrentDocument(processedPages)
                    }
                }
            }
        }
    }
    
    val launchScanner = {
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        activity?.let { act ->
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(act)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    )
                }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(stringResource(R.string.txt_selected_count, selectedPageIds.size), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    } else {
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var renameInput by remember { mutableStateOf(doc?.title ?: "") }

                        Column(modifier = Modifier.clickable { 
                            renameInput = doc?.title ?: ""
                            showRenameDialog = true 
                        }) {
                            Text(
                                text = doc?.title ?: stringResource(R.string.nav_documents),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (pages.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.txt_page_of, pagerState.currentPage + 1, pages.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    

                    if (showRenameDialog) {
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false },
                            title = { Text(stringResource(R.string.action_rename)) },
                            text = {
                                OutlinedTextField(
                                    value = renameInput,
                                    onValueChange = { renameInput = it },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                Button(onClick = {
                                    if (renameInput.isNotBlank() && doc != null) {
                                        viewModel.renameDocument(doc.id, renameInput.trim())
                                    }
                                    showRenameDialog = false
                                }) { Text(stringResource(R.string.txt_save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.txt_cancel)) }
                            }
                        )
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectionMode = false; selectedPageIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.txt_cancel_selection))
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showPdfExportDialog = true }, modifier = Modifier.testTag("export_pdf_top_btn")) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = stringResource(R.string.desc_export_pdf), tint = EmeraldLight)
                    }
                    if (pages.size > 1) {
                        IconButton(onClick = {
                            val allFiles = pages.map { File(it.processedImagePath) }
                            shareMultipleFiles(context, allFiles)
                        }) {
                            Icon(Icons.Default.Collections, contentDescription = stringResource(R.string.desc_share_all_jpegs))
                        }
                    }
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
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_save_to_gallery)) },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.saveDocumentToGallery(context, docId)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_print)) },
                                leadingIcon = { Icon(Icons.Default.Print, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    val activePage = pages.getOrNull(pagerState.currentPage)
                                    if (activePage != null) {
                                        val printHelper = androidx.print.PrintHelper(context)
                                        printHelper.scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
                                        val bitmap = android.graphics.BitmapFactory.decodeFile(activePage.processedImagePath)
                                        if (bitmap != null) {
                                            printHelper.printBitmap("DocScan Page", bitmap)
                                        }
                                    }
                                }
                            )
                            if (pagerState.currentPage > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.txt_move_left)) },
                                    leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val activePage = pages.getOrNull(pagerState.currentPage)
                                        if (activePage != null) {
                                            viewModel.movePageLeft(activePage.id)
                                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                        }
                                    }
                                )
                            }
                            if (pagerState.currentPage < pages.size - 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.txt_move_right)) },
                                    leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val activePage = pages.getOrNull(pagerState.currentPage)
                                        if (activePage != null) {
                                            viewModel.movePageRight(activePage.id)
                                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                        }
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
            androidx.compose.material3.FloatingActionButton(
                onClick = { launchScanner() },
                containerColor = EmeraldLight,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = stringResource(R.string.desc_add_page))
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                // Horizontal Thumbnails Strip (if multi-page)
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
                                    .size(44.dp, 60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (isCurrentPage) 2.5.dp else 1.dp,
                                        color = if (isCurrentPage) EmeraldLight else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(6.dp)
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
                                if (selectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(16.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(if (isPageSelected) EmeraldLight else Color.Black.copy(alpha = 0.4f)),
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activePage = pages.getOrNull(pagerState.currentPage)

                    // Duplicate
                    IconButton(
                        onClick = { viewModel.duplicateActivePage() },
                        modifier = Modifier.testTag("action_duplicate_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FileCopy, contentDescription = stringResource(R.string.desc_duplicate))
                            Text(stringResource(R.string.txt_copy), fontSize = 10.sp)
                        }
                    }

                    // Move Left (Reorder)
                    IconButton(
                        onClick = {
                            val curr = pagerState.currentPage
                            if (curr > 0) viewModel.reorderPages(curr, curr - 1)
                        },
                        modifier = Modifier.testTag("action_reorder_left_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ArrowBackIosNew, contentDescription = stringResource(R.string.desc_move_left), modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.txt_move), fontSize = 10.sp)
                        }
                    }

                    // Filters
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.testTag("action_filter_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ColorLens, contentDescription = stringResource(R.string.desc_filters), tint = CyanScan)
                            Text(stringResource(R.string.txt_filter), fontSize = 10.sp)
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
                            Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.desc_ocr), tint = EmeraldLight)
                            Text(stringResource(R.string.txt_ocr), fontSize = 10.sp)
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
                            Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.desc_sign___annotate))
                            Text(stringResource(R.string.txt_sign), fontSize = 10.sp)
                        }
                    }

                    // Rotate
                    IconButton(
                        onClick = { viewModel.rotateActivePage() },
                        modifier = Modifier.testTag("action_rotate_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.desc_rotate))
                            Text(stringResource(R.string.txt_rotate), fontSize = 10.sp)
                        }
                    }

                    // Delete
                    IconButton(
                        onClick = { 
                            viewModel.deleteActivePage()
                            if (pages.size == 1) {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("action_delete_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.desc_delete), tint = Color(0xFFEF4444))
                            Text(stringResource(R.string.txt_delete), fontSize = 10.sp, color = Color(0xFFEF4444))
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
                .background(Color(0xFF0F172A))
        ) {
            // Quality Report Banner
            val quality = uiState.currentQualityReport
            if (quality != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (quality.isBlurry || quality.isDark) Color(0xFF451A03) else Color(0xFF064E3B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (quality.isBlurry || quality.isDark) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (quality.isBlurry || quality.isDark) WarningAmber else EmeraldLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = quality.statusTextEn,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Main Document Page View (Horizontal Pager)
            if (pages.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                ) { index ->
                    val pageItem = pages[index]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
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
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EmeraldLight)
                }
            }
        }
    }

    // Filter Selection Bottom Sheet
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false }
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterType.values().forEach { filter ->
                        val activeFilter = pages.getOrNull(pagerState.currentPage)?.filterType
                        val isSelected = filter.name == activeFilter

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.applyFilterToActivePage(filter)
                                    showFilterSheet = false
                                }
                                .border(
                                    2.dp,
                                    if (isSelected) EmeraldLight else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(12.dp)
                                ),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val filterName = when (filter) {
                                    FilterType.ORIGINAL -> stringResource(R.string.filter_original)
                                    FilterType.MAGIC -> stringResource(R.string.filter_magic)
                                    FilterType.DOCUMENT -> stringResource(R.string.filter_document)
                                    FilterType.BLACK_WHITE -> stringResource(R.string.filter_bw)
                                    FilterType.GRAYSCALE -> stringResource(R.string.filter_grayscale)
                                    FilterType.VIBRANT -> stringResource(R.string.filter_vibrant)
                                }
                                Text(
                                    text = filterName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                if (pages.size > 1) {
                    Button(
                        onClick = {
                            val activeFilterStr = pages.getOrNull(pagerState.currentPage)?.filterType ?: FilterType.MAGIC.name
                            val activeFilter = try { FilterType.valueOf(activeFilterStr) } catch(e: Exception) { FilterType.MAGIC }
                            viewModel.applyFilterToAllPages(context, activeFilter)
                            showFilterSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.txt_apply_to_all_pages))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // PDF Export & Share Dialog
    if (showPdfExportDialog) {
        val uiState by viewModel.uiState.collectAsState()
        var selectedSize by remember { mutableStateOf(uiState.defaultPdfPageSize) }
        var selectedCompression by remember { mutableStateOf(uiState.defaultPdfCompression) }
        var includeOcr by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showPdfExportDialog = false },
            title = { Text(stringResource(R.string.txt_export_document_as_pdf)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.txt_page_format), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageSizePreset.values().forEach { size ->
                            val sizeName = when (size) {
                                PageSizePreset.A4 -> stringResource(R.string.page_size_a4)
                                PageSizePreset.LETTER -> stringResource(R.string.page_size_letter)
                                PageSizePreset.FIT_ORIGINAL -> stringResource(R.string.page_size_fit)
                                PageSizePreset.LEGAL -> stringResource(R.string.page_size_legal)
                            }
                            FilterChip(
                                selected = size == selectedSize,
                                onClick = { selectedSize = size },
                                label = { Text(sizeName) }
                            )
                        }
                    }

                    Text(stringResource(R.string.txt_quality___compression), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompressionPreset.values().forEach { comp ->
                            FilterChip(
                                selected = comp == selectedCompression,
                                onClick = { selectedCompression = comp },
                                label = { Text(comp.name) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.txt_searchable_ocr_text_layer), fontSize = 13.sp)
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
                    modifier = Modifier.testTag("export_pdf_confirm_btn")
                ) {
                    if (uiState.isExportingPdf) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text(stringResource(R.string.txt_export___share))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfExportDialog = false }) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}

private fun shareFile(context: android.content.Context, file: File, mimeType: String = "image/jpeg") {
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

private fun shareMultipleFiles(context: android.content.Context, files: List<File>, mimeType: String = "image/jpeg") {
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
