package com.example.ui.screens.home

import android.app.Activity
import android.net.Uri
import android.widget.Toast
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
import com.example.ui.components.AppUpdateDialog
import com.example.ui.components.ScanActionButton
import com.example.ui.screens.home.components.DocumentGridItem
import com.example.ui.screens.home.components.FolderGridItem
import com.example.ui.screens.home.components.NewFolderDialog
import com.example.ui.screens.home.components.RenameDocumentsDialog
import com.example.engine.updater.UpdateCheckState
import com.example.ui.theme.Emerald400
import com.example.ui.theme.*
import com.example.ui.viewmodel.DocumentViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    onNavigateToScan: (String) -> Unit = {},
    onNavigateToEditSession: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    val updateCheckState by viewModel.updateCheckState.collectAsState()
    val updateDownloadState by viewModel.updateDownloadState.collectAsState()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameBaseName by remember { mutableStateOf("") }
    var renameDocIds by remember { mutableStateOf(listOf<Long>()) }
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
                    viewModel.addPagesToPendingSession(pages)
                    onNavigateToEditSession()
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
                    val path = tempCameraFile!!.absolutePath
                    val exif = android.media.ExifInterface(path)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    val rotationDegrees = when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                    var bmp = android.graphics.BitmapFactory.decodeFile(path)
                    if (bmp != null) {
                        if (rotationDegrees != 0) {
                            val rotated = ImageProcessor.rotateBitmap(bmp, rotationDegrees)
                            if (rotated != bmp) {
                                bmp.recycle()
                                bmp = rotated
                            }
                        }
                        val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "scan_raw_")
                        val quad = ImageProcessor.detectDocumentQuad(bmp)
                        val proc = try {
                            val warped = ImageProcessor.warpPerspective(bmp, quad)
                            val filtered = ImageProcessor.applyFilter(warped, com.example.data.model.FilterType.MAGIC)
                            if (warped != bmp && warped != filtered) warped.recycle()
                            filtered
                        } catch (e: Exception) {
                            ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                        }
                        val procPath = ImageProcessor.saveBitmapToFile(context, proc, "scan_proc_")
                        if (bmp != proc) bmp.recycle()
                        proc.recycle()
                        viewModel.addPagesToPendingSession(listOf(Pair(rawPath, procPath)))
                        onNavigateToEditSession()
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
                            viewModel.addPagesToPendingSession(processedPages)
                            onNavigateToEditSession()
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
                        IconButton(onClick = {
                            renameDocIds = selectedDocIds.toList()
                            renameBaseName = ""
                            showRenameDialog = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = {
                            val docId = selectedDocIds.firstOrNull()
                            if (docId != null) {
                                viewModel.printDocumentById(context, docId)
                                selectionMode = false
                                selectedDocIds = emptySet()
                            }
                        }) {
                            Icon(Icons.Default.Print, contentDescription = "Print")
                        }
                        IconButton(onClick = { showTrashDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.txt_delete_selected), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "مرحباً بك",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = GoldBase
                            )
                        }
                    },
                    actions = {
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_select)) },
                                leadingIcon = { Icon(Icons.Default.Check, null) },
                                onClick = { expanded = false; selectionMode = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_grid_view)) },
                                leadingIcon = { Icon(Icons.Default.GridView, null) },
                                onClick = { expanded = false } // Grid view is default
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_sort)) },
                                leadingIcon = { Icon(Icons.Default.Sort, null) },
                                onClick = { expanded = false; /* TODO: Show sort dialog */ }
                            )
                            HorizontalDivider()
                            // Launcher for PDF import
                            val pdfPickerLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.OpenDocument()
                            ) { uri: Uri? ->
                                uri?.let {
                                    // Take persistable permission to access the URI later
                                    context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    
                                    // TODO: Implement PDF to Image conversion logic using PdfEngine
                                    // For now, toast the success
                                    Toast.makeText(context, "PDF Selected: ${it.lastPathSegment}", Toast.LENGTH_SHORT).show()
                                }
                            }

                            // ... (rest of your code)

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_import_pdf)) },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = { 
                                    expanded = false
                                    pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.desc_import_photos)) },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                onClick = { expanded = false; photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_new_folder)) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { expanded = false; showNewFolderDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_insert_blank_page)) },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                                onClick = { 
                                    expanded = false
                                    // Assuming a blank page is a white bitmap
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val blankBmp = android.graphics.Bitmap.createBitmap(2480, 3508, android.graphics.Bitmap.Config.ARGB_8888)
                                        blankBmp.eraseColor(android.graphics.Color.WHITE)
                                        val path = ImageProcessor.saveBitmapToFile(context, blankBmp, "blank_")
                                        blankBmp.recycle()
                                        withContext(Dispatchers.Main) {
                                            viewModel.importPagesAsDocument(listOf(Pair(path, path))) { newDocId ->
                                                onNavigateToDocument(newDocId)
                                            }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_settings_menu)) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { expanded = false; onNavigateToSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_about_app)) },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { expanded = false; Toast.makeText(context, "DocScan Pro v1.0", Toast.LENGTH_SHORT).show() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_feedback_menu)) },
                                leadingIcon = { Icon(Icons.Default.Feedback, null) },
                                onClick = { 
                                    expanded = false
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:support@example.com")
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Feedback for DocScan Pro")
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Send Feedback"))
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        floatingActionButton = {
            // FAB removed as requested
        },
        floatingActionButtonPosition = FabPosition.Center,
        bottomBar = {
            NavigationBar(
                containerColor = InkBase,
                modifier = Modifier.border(1.dp, InkBorder)
            ) {
                // Shared colors
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GoldBase,
                    indicatorColor = InkSurface2,
                    unselectedIconColor = TextSecondary
                )

                // Home
                NavigationBarItem(
                    selected = true,
                    onClick = { /* Already Home */ },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text(stringResource(R.string.nav_home)) },
                    colors = navItemColors
                )
                // Import
                NavigationBarItem(
                    selected = false,
                    onClick = {
                        // Need a way to handle photo picker callback here to add to pending session
                        // For now just note it needs update
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    icon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import") },
                    label = { Text(stringResource(R.string.txt_import)) },
                    colors = navItemColors
                )
                // Scan
                NavigationBarItem(
                    selected = false,
                    onClick = { onNavigateToScan("DOCUMENT") },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scan") },
                    label = { Text(stringResource(R.string.txt_scan)) },
                    colors = navItemColors
                )
                // Favorites
                NavigationBarItem(
                    selected = false,
                    onClick = { /* Navigate to Favorites */ },
                    icon = { Icon(Icons.Default.StarBorder, contentDescription = "Favorites") },
                    label = { Text(stringResource(R.string.nav_favorites)) },
                    colors = navItemColors
                )
                // Settings
                NavigationBarItem(
                    selected = false,
                    onClick = { onNavigateToSettings() },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    colors = navItemColors
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Persistent Search Bar
            if (!selectionMode) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_hint),
                            color = TextSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor   = InkSurface1,
                        unfocusedContainerColor = InkSurface1,
                        focusedBorderColor      = Color.Transparent,
                        unfocusedBorderColor    = Color.Transparent
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
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

    NewFolderDialog(
        show = showNewFolderDialog,
        onDismiss = { showNewFolderDialog = false },
        onConfirm = { name ->
            viewModel.filterByFolder(name)
            newFolderNameInput = ""
        }
    )

    RenameDocumentsDialog(
        show = showRenameDialog,
        onDismiss = { showRenameDialog = false },
        onConfirm = { name ->
            viewModel.renameDocuments(renameDocIds, name)
            selectionMode = false
            selectedDocIds = emptySet()
        }
    )

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
                            Text(if (isArabic) "تصوير مستند (تلقائي)" else "Scan Document (Auto)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (isArabic) "التقاط تلقائي ذكي مع تحديد حواف المستند" else "Intelligent auto capture & edge detection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(if (isArabic) "تصوير بطاقة" else "Scan ID Card", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (isArabic) "تصوير الوجهين الأمامي والخلفي ودمجهما باحترافية" else "Capture front & back and merge professionally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(if (isArabic) "تصوير متعدد (دفعة صفحات)" else "Batch Multi-Page Scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (isArabic) "تصوير متتالي وسريع لعدة صفحات في مستند واحد" else "Continuous rapid scanning into one document", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        onNavigateToScan("PASSPORT")
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
                                .background(Emerald400.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = Emerald400
                            )
                        }
                        Column {
                            Text(if (isArabic) "تصوير جواز" else "Scan Passport", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (isArabic) "إطار مخصص لصفحة بيانات وصورة الجواز" else "Dedicated frame for passport bio page & MRZ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // GitHub In-App Update Dialog
    
}