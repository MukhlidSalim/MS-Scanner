package com.example.ui.screens.home

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
import java.io.File
import com.example.R
import com.example.data.model.DocumentEntity
import com.example.engine.cv.ImageProcessor
import com.example.ui.components.CategoryChipsRow
import com.example.ui.components.FolderChipsRow
import com.example.ui.components.PdfViewerOverlay
import com.example.ui.components.ScanActionButton
import com.example.data.repository.AppPreferences
import com.example.engine.pdf.PdfEngine
import com.example.ui.screens.home.components.DocumentGridItem
import com.example.ui.screens.home.components.FolderGridItem
import com.example.ui.screens.home.components.ExportPdfDialog
import com.example.ui.screens.home.components.NewFolderDialog
import com.example.ui.screens.home.components.RenameDocumentsDialog
import com.example.ui.viewmodel.ExportPdfAction
import com.example.ui.theme.Emerald400
import com.example.ui.theme.*
import com.example.ui.viewmodel.DocumentListViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    listViewModel: DocumentListViewModel,
    onPagesCaptured: (List<Pair<String, String>>) -> Unit = {},
    onImportedUris: (List<Uri>) -> Unit = {},
    onOpenPdfFile: (File) -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToDocument: (Long) -> Unit,
    onNavigateToScan: (String) -> Unit = {},
    onNavigateToEditSession: (String, Long) -> Unit = { _, _ -> }
) {
    val uiState by listViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    val prefs = remember { AppPreferences(context) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameBaseName by remember { mutableStateOf("") }
    var renameDocIds by remember { mutableStateOf(listOf<Long>()) }
    var docToMove by remember { mutableStateOf<Long?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showExportPdfDialog by remember { mutableStateOf(false) }
    var pendingExportConfig by remember { mutableStateOf<com.example.engine.pdf.PdfExportConfig?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var newFolderRename by remember { mutableStateOf("") }
    var showSortSheet by remember { mutableStateOf(false) }

    var tempCameraFile by remember { mutableStateOf<File?>(null) }

    // System File Picker for importing / opening PDF files
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {}
                val localPdf = PdfEngine.copyUriToLocalPdf(context, it)
                if (localPdf != null) {
                    onOpenPdfFile(localPdf)
                } else {
                    Toast.makeText(context, "Could not open PDF file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SAF Create Document launcher for PDF export
    val createPdfDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null && pendingExportConfig != null) {
            val config = pendingExportConfig!!
            listViewModel.exportDocumentsAsPdf(
                context = context,
                docIds = selectedDocIds.toList(),
                config = config,
                action = ExportPdfAction.SAVE_AS,
                targetSaveUri = uri,
                onSuccess = { _, _, path ->
                    showExportPdfDialog = false
                    selectionMode = false
                    selectedDocIds = emptySet()
                    pendingExportConfig = null
                    Toast.makeText(context, "Saved to $path", Toast.LENGTH_LONG).show()
                },
                onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onImportedUris(uris)
            onNavigateToEditSession("IMPORT", 0L)
        }
    }

    val launchGalleryImport = {
        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraFile != null && tempCameraFile!!.exists()) {
            coroutineScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
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
                        var bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return@withContext null
                        try {
                            if (rotationDegrees != 0) {
                                val rotated = ImageProcessor.rotateBitmap(bmp, rotationDegrees)
                                if (rotated !== bmp) { bmp.recycle(); bmp = rotated }
                            }
                            val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "scan_raw_")
                            val quad = ImageProcessor.detectDocumentQuad(bmp)
                            val procBmp = ImageProcessor.applyFilter(bmp, com.example.data.model.FilterType.MAGIC)
                            val procPath = ImageProcessor.saveBitmapToFile(context, procBmp, "scan_proc_")
                            procBmp.recycle()
                            Pair(rawPath, procPath)
                        } finally {
                            bmp.recycle()
                        }
                    }
                    if (result != null) {
                        onPagesCaptured(listOf(result))
                        onNavigateToEditSession("CAMERA", 0L)
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
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            launchGalleryImport()
        }
    }

    BackHandler(enabled = selectionMode || uiState.selectedFolder != "ALL" || showSearch || uiState.showFavoritesOnly) {
        if (selectionMode) {
            selectionMode = false
            selectedDocIds = emptySet()
        } else if (showSearch) {
            showSearch = false
            listViewModel.onSearchQueryChanged("")
        } else if (uiState.showFavoritesOnly) {
            listViewModel.toggleFavoritesFilter()
        } else if (uiState.selectedFolder != "ALL") {
            listViewModel.filterByFolder("ALL")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        listViewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.ShowSnackbarWithAction -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) { event.action() }
                }
                is com.example.ui.util.UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedDocIds.size} Selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectionMode = false; selectedDocIds = emptySet() }) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { 
                            renameDocIds = selectedDocIds.toList()
                            renameBaseName = ""
                            showRenameDialog = true
                        }) { Icon(Icons.Default.Edit, null) }
                        
                        IconButton(onClick = { showTrashDialog = true }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(text = if (isArabic) "مرحباً بك" else "Welcome", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = if (isArabic) "إليك مستنداتك الأخيرة" else "Here are your recent documents", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, null) }
                        IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, null) }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Column(horizontalAlignment = Alignment.End) {
                    // Secondary FABs for Import
                    SmallFloatingActionButton(
                        onClick = launchGalleryImport,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) { Icon(Icons.Default.PhotoLibrary, null) }
                    
                    SmallFloatingActionButton(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) { Icon(Icons.Default.PictureAsPdf, null) }

                    // Main Scan FAB
                    FloatingActionButton(
                        onClick = { onNavigateToScan("DOCUMENT") },
                        containerColor = Emerald400,
                        contentColor = Color.Black,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CameraAlt, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isArabic) "مسح مستند" else "Scan Document", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (showSearch) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { listViewModel.onSearchQueryChanged(it) },
                    placeholder = { Text(if (isArabic) "ابحث في المستندات..." else "Search documents...") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { showSearch = false; listViewModel.onSearchQueryChanged("") }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            val folders = uiState.folders.filter { it != "Default" && it != "ALL" }
            
            // Folder and Category Strips
            if (!selectionMode && uiState.searchQuery.isEmpty()) {
                FolderChipsRow(
                    folders = folders,
                    selectedFolder = uiState.selectedFolder,
                    onFolderSelected = { listViewModel.filterByFolder(it) },
                    onCreateFolderClick = { showNewFolderDialog = true }
                )
                CategoryChipsRow(
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = { listViewModel.filterByCategory(it) }
                )
            }

            val docs = uiState.documents
            if (docs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Text(if (isArabic) "لا توجد مستندات" else "No documents found", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(docs) { doc ->
                        DocumentGridItem(
                            doc = doc,
                            searchQuery = uiState.searchQuery,
                            isSelected = selectedDocIds.contains(doc.id),
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id)
                                }
                            },
                            onEditClick = { onNavigateToEditSession("EXISTING", doc.id) },
                            onLongClick = {
                                selectionMode = true
                                selectedDocIds = setOf(doc.id)
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showNewFolderDialog) {
        NewFolderDialog(
            show = showNewFolderDialog,
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { /* listViewModel.createFolder(it) */ showNewFolderDialog = false }
        )
    }

    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text(if (isArabic) "نقل إلى المحذوفات" else "Move to Trash") },
            text = { Text(if (isArabic) "هل أنت متأكد من نقل المستندات المختارة إلى سلة المحذوفات؟" else "Are you sure you want to move selected documents to trash?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedDocIds.forEach { listViewModel.moveToTrash(it) }
                    showTrashDialog = false
                    selectionMode = false
                    selectedDocIds = emptySet()
                }) { Text(if (isArabic) "نقل" else "Move", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showTrashDialog = false }) { Text(if (isArabic) "إلغاء" else "Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        RenameDocumentsDialog(
            show = showRenameDialog,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                listViewModel.renameDocuments(renameDocIds, newName)
                showRenameDialog = false
                selectionMode = false
                selectedDocIds = emptySet()
            }
        )
    }
    
    if (showExportPdfDialog) {
        ExportPdfDialog(
            show = showExportPdfDialog,
            selectedDocuments = uiState.documents.filter { selectedDocIds.contains(it.id) },
            totalPageCount = uiState.documents.filter { selectedDocIds.contains(it.id) }.sumOf { it.pageCount },
            isExporting = false,
            onDismiss = { showExportPdfDialog = false },
            onExportAction = { config, action ->
                pendingExportConfig = config
                if (action == ExportPdfAction.SAVE_AS) {
                    createPdfDocumentLauncher.launch(config.title + ".pdf")
                } else {
                    listViewModel.exportDocumentsAsPdf(context, selectedDocIds.toList(), config, action)
                }
            }
        )
    }
}
