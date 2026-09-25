package com.example.ui.screens.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DocumentEntity
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.CyanScan
import com.example.ui.viewmodel.DocumentViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: DocumentViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDocument: (Long) -> Unit,
    onNavigateToScan: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var docToMove by remember { mutableStateOf<Long?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    
    // Scanner Options
    val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(20)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

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
                        viewModel.importPagesAsDocument(processedPages) { newDocId ->
                            onNavigateToDocument(newDocId)
                        }
                    }
                }
            }
        }
    }

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
        val activity = context as? Activity
        if (activity != null) {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener {
                    onNavigateToScan() // Fallback
                }
        }
    }

    // Handle Back Press for Folder & Selection Mode
    BackHandler(enabled = selectionMode || uiState.selectedFolder != "ALL") {
        if (selectionMode) {
            selectionMode = false
            selectedDocIds = emptySet()
        } else if (uiState.selectedFolder != "ALL") {
            viewModel.filterByFolder("ALL")
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.txt_selected_count, selectedDocIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedDocIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.txt_cancel_selection))
                        }
                    },
                    actions = {
                        if (selectedDocIds.size >= 2) {
                            IconButton(onClick = {
                                viewModel.mergeDocuments(selectedDocIds.toList())
                                selectionMode = false
                                selectedDocIds = emptySet()
                            }) {
                                Icon(Icons.Default.MergeType, contentDescription = "Merge")
                            }
                        }
                        IconButton(onClick = {
                            // Move multiple
                            docToMove = selectedDocIds.firstOrNull() // Simplify: or show multi-move dialog
                            // For simplicity, just use single move dialog logic expanded, or custom multi-move.
                            // We will reuse docToMove but handle it as a trigger for a dialog that can move ALL selected.
                        }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Move")
                        }
                        IconButton(onClick = {
                            viewModel.shareDocumentsAsPdf(context, selectedDocIds.toList())
                            selectionMode = false
                            selectedDocIds = emptySet()
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { showTrashDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.txt_delete_selected))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            } else if (showSearch) {
                TopAppBar(
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            showSearch = false
                            viewModel.onSearchQueryChanged("")
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = if (uiState.selectedFolder == "ALL") stringResource(R.string.app_name) else uiState.selectedFolder,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        if (uiState.selectedFolder != "ALL") {
                            IconButton(onClick = { viewModel.filterByFolder("ALL") }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        containerColor = Color(0xFF2196F3),
                        contentColor = Color.White
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, contentDescription = "Import")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import")
                        }
                    }
                    FloatingActionButton(
                        onClick = { launchScanner() },
                        containerColor = CyanScan,
                        contentColor = Color.White
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Camera")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Camera")
                        }
                    }
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val displayDocs = if (uiState.selectedFolder == "ALL" && uiState.searchQuery.isEmpty()) {
                uiState.documents.filter { it.folderName == "Default" || it.folderName == "ALL" }
            } else {
                uiState.documents
            }
            
            val folders = uiState.folders.filter { it != "Default" && it != "ALL" }

            if (uiState.documents.isEmpty() && folders.isEmpty() && uiState.searchQuery.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.txt_no_documents), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.txt_no_documents_body), color = Color.Gray)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Folders (Only show in ALL view or when searching)
                    if ((uiState.selectedFolder == "ALL" || uiState.searchQuery.isNotEmpty()) && folders.isNotEmpty()) {
                        val filteredFolders = folders.filter { it.contains(uiState.searchQuery, ignoreCase = true) }
                        items(filteredFolders) { folder ->
                            FolderGridItem(
                                folderName = folder,
                                documentCount = uiState.documents.count { it.folderName == folder },
                                onClick = { viewModel.filterByFolder(folder) }
                            )
                        }
                    }

                    // Documents
                    items(displayDocs) { doc ->
                        DocumentGridItem(
                            doc = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    if (selectedDocIds.contains(doc.id)) selectedDocIds -= doc.id else selectedDocIds += doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id)
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selectedDocIds += doc.id
                            }
                        )
                    }
                }
            }
        }
    }

        if (docToMove != null) {
        var selectedFolderDest by remember { mutableStateOf("Default") }
        AlertDialog(
            onDismissRequest = { docToMove = null },
            title = { Text(stringResource(R.string.txt_move_to_folder)) },
            text = {
                Column {
                    uiState.folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedFolderDest == folder, onClick = { selectedFolderDest = folder })
                            Text(folder)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedDocIds.isNotEmpty()) {
                        selectedDocIds.forEach { viewModel.changeDocumentFolder(it, selectedFolderDest) }
                    } else {
                        viewModel.changeDocumentFolder(docToMove!!, selectedFolderDest)
                    }
                    docToMove = null
                    selectionMode = false
                    selectedDocIds = emptySet()
                }) { Text(stringResource(R.string.txt_save)) }
            },
            dismissButton = {
                TextButton(onClick = { docToMove = null }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }

    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text(stringResource(R.string.txt_move_to_trash)) },
            text = { Text("Move ${selectedDocIds.size} documents to trash?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedDocIds.forEach { viewModel.moveToTrash(it) }
                        selectionMode = false
                        selectedDocIds = emptySet()
                        showTrashDialog = false
                    }
                ) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrashDialog = false }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridItem(
    doc: DocumentEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.LightGray.copy(alpha = 0.3f))
                ) {
                    if (doc.thumbnailPath.isNotEmpty()) {
                        AsyncImage(
                            model = File(doc.thumbnailPath),
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(40.dp),
                            tint = Color.Gray
                        )
                    }
                }
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = doc.title,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${doc.pageCount} Pages • ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(doc.updatedAt))}",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        maxLines = 1
                    )
                }
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) Color.Black.copy(alpha = 0.3f) else Color.Transparent)
                )
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridItem(
    folderName: String,
    documentCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = CyanScan
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = folderName,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp
            )
            Text(
                text = "$documentCount items",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}
