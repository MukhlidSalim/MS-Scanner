package com.example.ui.screens.home

import androidx.compose.ui.res.stringResource
import com.example.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentEntity
import com.example.engine.cv.ImageProcessor
import com.example.ui.components.CategoryChipsRow
import com.example.ui.components.DocumentCard
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight
import com.example.ui.viewmodel.DocumentViewModel
import kotlinx.coroutines.launch
import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: DocumentViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToDocument: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var isGridView by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
    var isIdCardMode by remember { mutableStateOf(false) }
    var pendingIdCardPages by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    
    var updateInfo by remember { mutableStateOf<com.example.engine.updater.AppUpdater.UpdateInfo?>(null) }
    
    LaunchedEffect(Unit) {
        val info = com.example.engine.updater.AppUpdater.checkForUpdate()
        if (info != null) {
            updateInfo = info
        }
    }
    
    if (updateInfo != null) {
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text(stringResource(R.string.txt_update_available)) },
            text = { Text("Version ${updateInfo?.version} is available!\n\n${updateInfo?.releaseNotes}") },
            confirmButton = {
                Button(onClick = {
                    com.example.engine.updater.AppUpdater.downloadAndInstall(context, updateInfo!!.downloadUrl)
                    updateInfo = null
                }) {
                    Text(stringResource(R.string.txt_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) { Text(stringResource(R.string.txt_later)) }
            }
        )
    }

    if (pendingIdCardPages != null) {
        com.example.ui.screens.idcard.IdCardMergerScreen(
            frontImagePath = pendingIdCardPages!![0].second,
            backImagePath = pendingIdCardPages!![1].second,
            onMerged = { mergedPath ->
                viewModel.importPagesAsDocument(listOf(Pair(pendingIdCardPages!![0].first, mergedPath))) { newDocId ->
                    pendingIdCardPages = null
                    isIdCardMode = false
                    onNavigateToDocument(newDocId)
                }
            },
            onCancel = {
                pendingIdCardPages = null
                isIdCardMode = false
            }
        )
        return // Overlay
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                coroutineScope.launch {
                    val processedPages = mutableListOf<Pair<String, String>>()
                    for (page in pages) {
                        val stream = context.contentResolver.openInputStream(page.imageUri)
                        val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) {
                            val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "scan_raw_")
                            val proc = ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                            val procPath = ImageProcessor.saveBitmapToFile(context, proc, "scan_proc_")
                            processedPages.add(Pair(rawPath, procPath))
                            
                            if (bmp != proc) bmp.recycle()
                            proc.recycle()
                        }
                    }
                    if (processedPages.isNotEmpty()) {
                        if (isIdCardMode && processedPages.size >= 2) {
                            pendingIdCardPages = processedPages
                        } else {
                            viewModel.importPagesAsDocument(processedPages) { newDocId ->
                                onNavigateToDocument(newDocId)
                            }
                        }
                    }
                }
            }
        }
    }

    // System Photo Picker for multi-image import
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val pages = mutableListOf<Pair<String, String>>()
                for (uri in uris) {
                    val stream = context.contentResolver.openInputStream(uri)
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) {
                        val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "import_raw_")
                        val proc = ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                        val procPath = ImageProcessor.saveBitmapToFile(context, proc, "import_proc_")
                        pages.add(Pair(rawPath, procPath))
                        
                        if (bmp != proc) bmp.recycle()
                        proc.recycle()
                    }
                }
                if (pages.isNotEmpty()) {
                    viewModel.importPagesAsDocument(pages) { newDocId ->
                        onNavigateToDocument(newDocId)
                    }
                }
            }
        }
    }

    val launchScanner = {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (isIdCardMode) 2 else 20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        activity?.let { act ->
            GmsDocumentScanning.getClient(options).getStartScanIntent(act)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener {
                    onNavigateToScan()
                }
        } ?: onNavigateToScan()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectionMode = false; selectedDocIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(EmeraldLight, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = if (selectionMode) "${selectedDocIds.size} Selected" else "DocScan Pro",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectedDocIds.forEach { viewModel.moveToTrash(it) }
                            selectionMode = false
                            selectedDocIds = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                    IconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier.testTag("toggle_view_btn")
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            contentDescription = stringResource(R.string.desc_toggle_grid_list_view)
                        )
                    }
                    IconButton(
                        onClick = { showTrashDialog = true },
                        modifier = Modifier.testTag("trash_bin_btn")
                    ) {
                        BadgedBox(badge = {
                            if (uiState.trashDocuments.isNotEmpty()) {
                                Badge { Text("${uiState.trashDocuments.size}") }
                            }
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.desc_trash_bin))
                        }
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.desc_settings))
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                // Secondary FAB: Import from Gallery
                SmallFloatingActionButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("import_photos_fab")
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.desc_import_photos))
                }

                // ID Card Scanner
                ExtendedFloatingActionButton(
                    onClick = { 
                        isIdCardMode = true
                        launchScanner() 
                    },
                    containerColor = CyanScan,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Badge, contentDescription = null) },
                    text = { Text(stringResource(R.string.txt_id_card), fontWeight = FontWeight.Bold) }
                )

                // Primary FAB: Camera Scanner
                ExtendedFloatingActionButton(
                    onClick = { 
                        isIdCardMode = false
                        launchScanner() 
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text(stringResource(R.string.txt_scan_document), fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("main_scan_fab")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text(stringResource(R.string.txt_search_titles__tags__or_oc)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_clear_search))
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_text_field")
            )

            // Category Filter Chips
            CategoryChipsRow(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.filterByCategory(it) }
            )

            // Document Count & Quick Filter Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${uiState.documents.size} Documents",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            // Documents List / Grid / Empty State
            if (uiState.documents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        Text(
                            text = if (uiState.searchQuery.isNotEmpty()) "No matching documents" else "No Scanned Documents Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = if (uiState.searchQuery.isNotEmpty()) {
                                "Try searching for a different keyword or OCR term."
                            } else {
                                "Tap 'Scan Document' to capture your first invoice, receipt, or ID card with intelligent edge detection."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { launchScanner() },
                            modifier = Modifier.testTag("empty_state_scan_btn")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.txt_start_scanning))
                        }
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.documents, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            onClick = { 
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id) 
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedDocIds = setOf(doc.id)
                                }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(doc.id) },
                            onDelete = { viewModel.moveToTrash(doc.id) },
                            onRename = { newTitle -> viewModel.renameDocument(doc.id, newTitle) },
                            onSharePdf = { onNavigateToDocument(doc.id) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.documents, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            onClick = { 
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id) 
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedDocIds = setOf(doc.id)
                                }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(doc.id) },
                            onDelete = { viewModel.moveToTrash(doc.id) },
                            onRename = { newTitle -> viewModel.renameDocument(doc.id, newTitle) },
                            onSharePdf = { onNavigateToDocument(doc.id) }
                        )
                    }
                }
            }
        }
    }

    // Trash Bin Management Dialog
    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text("Trash Bin (${uiState.trashDocuments.size})") },
            text = {
                if (uiState.trashDocuments.isEmpty()) {
                    Text(stringResource(R.string.txt_trash_is_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.trashDocuments) { doc ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(doc.title, maxLines = 1, modifier = Modifier.weight(1f))
                                Row {
                                    IconButton(onClick = { viewModel.restoreFromTrash(doc.id) }) {
                                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.desc_restore))
                                    }
                                    IconButton(onClick = { viewModel.deletePermanently(doc.id) }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.desc_delete), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (uiState.trashDocuments.isNotEmpty()) {
                    TextButton(onClick = {
                        viewModel.emptyTrash()
                        showTrashDialog = false
                    }) {
                        Text(stringResource(R.string.txt_empty_trash), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrashDialog = false }) {
                    Text(stringResource(R.string.txt_close))
                }
            }
        )
    }
}
