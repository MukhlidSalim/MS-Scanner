package com.example.ui.screens.home

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.components.CategoryChipsRow
import com.example.ui.components.FolderChipsRow
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
    onNavigateToScan: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var docToMove by remember { mutableStateOf<Long?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showCameraSheet by remember { mutableStateOf(false) }
    var isIdCardMode by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var newFolderRename by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }
    
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

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

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraFile != null && tempCameraFile!!.exists()) {
            coroutineScope.launch {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(tempCameraFile!!.absolutePath)
                    if (bmp != null) {
                        val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "scan_raw_")
                        val proc = ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                        val procPath = ImageProcessor.saveBitmapToFile(context, proc, "scan_proc_")
                        if (bmp != proc) bmp.recycle()
                        proc.recycle()
                        viewModel.importPagesAsDocument(listOf(Pair(rawPath, procPath))) { newDocId ->
                            onNavigateToDocument(newDocId)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val launchCameraCapture = {
        try {
            val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    // Scanner Options
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
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
                                val collagePath = ImageProcessor.createIdCardCollage(context, processedPages[0].second, processedPages[1].second, "idcard_proc_")
                                viewModel.importPagesAsDocument(listOf(Pair(processedPages[0].first, collagePath))) { newDocId ->
                                    onNavigateToDocument(newDocId)
                                }
                            } else {
                                viewModel.importPagesAsDocument(processedPages) { newDocId ->
                                    onNavigateToDocument(newDocId)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            launchCameraCapture()
        }
    }

    val launchScanner: (Int) -> Unit = { limit ->
        val activity = context as? Activity
        if (activity != null) {
            try {
                val dynOptions = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(limit)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(dynOptions).getStartScanIntent(activity)
                    .addOnSuccessListener { intentSender ->
                        try {
                            scannerLauncher.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            launchCameraCapture()
                        }
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        launchCameraCapture()
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                launchCameraCapture()
            }
        } else {
            launchCameraCapture()
        }
    }

    // Handle Back Press for Folder & Selection Mode
    BackHandler(enabled = selectionMode || uiState.selectedFolder != "ALL" || showSearch) {
        if (selectionMode) {
            selectionMode = false
            selectedDocIds = emptySet()
        } else if (showSearch) {
            showSearch = false
            viewModel.onSearchQueryChanged("")
        } else if (uiState.selectedFolder != "ALL") {
            viewModel.filterByFolder("ALL")
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.txt_selected_count, selectedDocIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
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
                                Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = "Merge")
                            }
                        }
                        IconButton(onClick = {
                            docToMove = selectedDocIds.firstOrNull()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move")
                        }
                        IconButton(onClick = {
                            viewModel.shareDocumentsAsPdf(context, selectedDocIds.toList())
                            selectionMode = false
                            selectedDocIds = emptySet()
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { showTrashDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.txt_delete_selected), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else if (showSearch) {
                TopAppBar(
                    title = {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            TextField(
                                value = uiState.searchQuery,
                                onValueChange = { viewModel.onSearchQueryChanged(it) },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.search_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            showSearch = false
                            viewModel.onSearchQueryChanged("")
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (uiState.selectedFolder == "ALL") stringResource(R.string.app_name) else uiState.selectedFolder,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            if (uiState.selectedFolder == "ALL") {
                                Text(
                                    text = stringResource(R.string.tagline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (uiState.selectedFolder != "ALL") {
                            IconButton(onClick = { viewModel.filterByFolder("ALL") }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                // Sleek integrated floating scanner pill
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ),
                        width = 1.dp
                    ),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Quick Import
                        TextButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = "Import",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Import",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        // Main Scan Button (Glowing Executive Emerald)
                        Button(
                            onClick = { showCameraSheet = true },
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Camera",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.nav_scan),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
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

            // Category Chips Row (Filtering)
            if (!selectionMode && uiState.searchQuery.isEmpty()) {
                CategoryChipsRow(
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = { viewModel.filterByCategory(it) }
                )
            }

            // Folder Filter Strip
            if (folders.isNotEmpty() && !selectionMode && uiState.searchQuery.isEmpty()) {
                FolderChipsRow(
                    folders = folders,
                    selectedFolder = uiState.selectedFolder,
                    onFolderSelected = { viewModel.filterByFolder(it) },
                    onCreateFolderClick = { showNewFolderDialog = true },
                    onRenameFolder = { oldName, newName -> viewModel.renameFolder(oldName, newName) },
                    onDeleteFolder = { viewModel.deleteFolder(it) }
                )
            }

            if (uiState.documents.isEmpty() && folders.isEmpty() && uiState.searchQuery.isEmpty()) {
                // Luxury Clean Empty State
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.txt_no_documents),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.txt_no_documents_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = { showCameraSheet = true },
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.txt_start_scanning), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
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
                                onClick = { viewModel.filterByFolder(folder) },
                                onRenameClick = { renameFolderTarget = folder; newFolderRename = folder }
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

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.txt_create_folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderNameInput,
                    onValueChange = { newFolderNameInput = it },
                    label = { Text(stringResource(R.string.txt_folder_name)) },
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
                        if (newFolderNameInput.isNotBlank()) {
                            viewModel.filterByFolder(newFolderNameInput.trim())
                            newFolderNameInput = ""
                        }
                        showNewFolderDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.txt_create), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    if (docToMove != null) {
        var selectedFolderDest by remember { mutableStateOf("Default") }
        AlertDialog(
            onDismissRequest = { docToMove = null },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.txt_move_to_folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    uiState.folders.forEach { folder ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedFolderDest == folder) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolderDest = folder }
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                RadioButton(selected = selectedFolderDest == folder, onClick = { selectedFolderDest = folder })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(folder, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedDocIds.isNotEmpty()) {
                            selectedDocIds.forEach { viewModel.changeDocumentFolder(it, selectedFolderDest) }
                        } else {
                            viewModel.changeDocumentFolder(docToMove!!, selectedFolderDest)
                        }
                        docToMove = null
                        selectionMode = false
                        selectedDocIds = emptySet()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { docToMove = null }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    if (showCameraSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCameraSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.txt_scan_document),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                    ),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showCameraSheet = false
                        onNavigateToScan("DOCUMENT")
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column {
                            Text(stringResource(R.string.txt_scan_doc), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Intelligent auto capture & edge detection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                    ),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showCameraSheet = false
                        onNavigateToScan("ID_CARD")
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Badge,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Column {
                            Text(stringResource(R.string.txt_scan_id_card), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Capture front & back and merge professionally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                    ),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showCameraSheet = false
                        onNavigateToScan("BATCH")
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.BurstMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Column {
                            Text("Batch Multi-Page Scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Continuous rapid scanning into one document", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (renameFolderTarget != null) {
        AlertDialog(
            onDismissRequest = { renameFolderTarget = null },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.txt_rename_folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderRename,
                    onValueChange = { newFolderRename = it },
                    label = { Text(stringResource(R.string.txt_new_folder_name)) },
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
                        if (newFolderRename.isNotBlank()) {
                            viewModel.renameFolder(renameFolderTarget!!, newFolderRename)
                        }
                        renameFolderTarget = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameFolderTarget = null }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.txt_move_to_trash), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            text = { Text("Move ${selectedDocIds.size} documents to trash?", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        selectedDocIds.forEach { viewModel.moveToTrash(it) }
                        selectionMode = false
                        selectedDocIds = emptySet()
                        showTrashDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.desc_delete), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrashDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
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
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            ),
            width = if (isSelected) 1.5.dp else 1.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 0.5.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Thumbnail Paper Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    if (doc.thumbnailPath.isNotEmpty() && File(doc.thumbnailPath).exists()) {
                        AsyncImage(
                            model = File(doc.thumbnailPath),
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(36.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }

                    // Page count pill at bottom-right of preview
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.75f)
                    ) {
                        Text(
                            text = "${doc.pageCount}p",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Metadata Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(doc.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Selection Checkbox Badge
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridItem(
    folderName: String,
    documentCount: Int,
    onClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            ),
            width = 1.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.txt_rename_folder)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                expanded = false
                                onRenameClick()
                            }
                        )
                    }
                }
            }

            Column {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$documentCount documents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
