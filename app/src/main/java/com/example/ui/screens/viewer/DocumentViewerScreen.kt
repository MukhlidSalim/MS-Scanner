package com.example.ui.screens.viewer

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

    LaunchedEffect(pagerState.currentPage) {
        if (pages.isNotEmpty() && pagerState.currentPage in pages.indices) {
            viewModel.selectPageIndex(pagerState.currentPage)
        }
    }

    var showPdfExportDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    
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
                    Column {
                        Text(
                            text = doc?.title ?: "Document",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (pages.isNotEmpty()) {
                            Text(
                                text = "Page ${pagerState.currentPage + 1} of ${pages.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showPdfExportDialog = true }, modifier = Modifier.testTag("export_pdf_top_btn")) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Export PDF", tint = EmeraldLight)
                    }
                    if (pages.size > 1) {
                        IconButton(onClick = {
                            val allFiles = pages.map { File(it.processedImagePath) }
                            shareMultipleFiles(context, allFiles)
                        }) {
                            Icon(Icons.Default.Collections, contentDescription = "Share All JPEGs")
                        }
                    }
                    IconButton(onClick = {
                        val activePage = pages.getOrNull(pagerState.currentPage) ?: return@IconButton
                        shareFile(context, File(activePage.processedImagePath))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Page")
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
                Icon(Icons.Default.AddAPhoto, contentDescription = "Add Page")
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
                            val isSelected = index == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(44.dp, 60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) EmeraldLight else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        // Scroll to selected page
                                    }
                            ) {
                                AsyncImage(
                                    model = File(p.processedImagePath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
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
                            Icon(Icons.Default.FileCopy, contentDescription = "Duplicate")
                            Text("Copy", fontSize = 10.sp)
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
                            Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Move Left", modifier = Modifier.size(20.dp))
                            Text("Move", fontSize = 10.sp)
                        }
                    }

                    // Filters
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.testTag("action_filter_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ColorLens, contentDescription = "Filters", tint = CyanScan)
                            Text("Filter", fontSize = 10.sp)
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
                            Icon(Icons.Default.TextFields, contentDescription = "OCR", tint = EmeraldLight)
                            Text("OCR", fontSize = 10.sp)
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
                            Icon(Icons.Default.Draw, contentDescription = "Sign & Annotate")
                            Text("Sign", fontSize = 10.sp)
                        }
                    }

                    // Rotate
                    IconButton(
                        onClick = { viewModel.rotateActivePage() },
                        modifier = Modifier.testTag("action_rotate_btn")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate")
                            Text("Rotate", fontSize = 10.sp)
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
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFEF4444))
                            Text("Delete", fontSize = 10.sp, color = Color(0xFFEF4444))
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
                            contentDescription = "Page ${index + 1}",
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
                    text = "Document Filters",
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
                                Text(
                                    text = filter.name.replace("_", " "),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // PDF Export & Share Dialog
    if (showPdfExportDialog) {
        var selectedSize by remember { mutableStateOf(PageSizePreset.A4) }
        var selectedCompression by remember { mutableStateOf(CompressionPreset.HIGH) }
        var includeOcr by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showPdfExportDialog = false },
            title = { Text("Export Document as PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Page Format", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageSizePreset.values().forEach { size ->
                            FilterChip(
                                selected = size == selectedSize,
                                onClick = { selectedSize = size },
                                label = { Text(size.name) }
                            )
                        }
                    }

                    Text("Quality & Compression", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                        Text("Searchable OCR Text Layer", fontSize = 13.sp)
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
                        viewModel.exportDocumentToPdf(config) { generatedPdf ->
                            showPdfExportDialog = false
                            shareFile(context, generatedPdf, "application/pdf")
                        }
                    },
                    modifier = Modifier.testTag("export_pdf_confirm_btn")
                ) {
                    if (uiState.isExportingPdf) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Export & Share")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfExportDialog = false }) {
                    Text("Cancel")
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
