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
import com.example.engine.cv.DocumentPipeline
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    var isProcessingCapture by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // Targets of the single-document menu or of the selection (Export / Move use their own lists).
    var exportDocIds by remember { mutableStateOf(listOf<Long>()) }
    var moveDocIds by remember { mutableStateOf(listOf<Long>()) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showTrashSheet by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selectedDocIds = emptySet() }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
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
                    Toast.makeText(context, if (isArabic) "تعذر فتح ملف PDF" else "Could not open PDF file", Toast.LENGTH_SHORT).show()
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
                docIds = exportDocIds,
                config = config,
                action = ExportPdfAction.SAVE_AS,
                targetSaveUri = uri,
                onSuccess = { _, _, path ->
                    showExportPdfDialog = false
                    selectionMode = false
                    selectedDocIds = emptySet()
                    pendingExportConfig = null
                    Toast.makeText(context, if (isArabic) "تم الحفظ" else "Saved", Toast.LENGTH_LONG).show()
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
    // "Files" import: images and PDF from any storage provider (Drive, Downloads, SD card…).
    val filesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val types = uris.associateWith { context.contentResolver.getType(it).orEmpty() }
            val images = uris.filter { types[it]!!.startsWith("image/") }
            val pdf = uris.firstOrNull { types[it] == "application/pdf" }
            when {
                images.isNotEmpty() -> {
                    onImportedUris(images)
                    onNavigateToEditSession("IMPORT", 0L)
                    if (pdf != null) Toast.makeText(context, if (isArabic) "تم استيراد الصور؛ استورد ملف PDF على حدة" else "Images imported; import the PDF separately", Toast.LENGTH_LONG).show()
                }
                pdf != null -> {
                    val local = PdfEngine.copyUriToLocalPdf(context, pdf)
                    if (local != null) onOpenPdfFile(local)
                    else Toast.makeText(context, if (isArabic) "تعذر فتح ملف PDF" else "Could not open PDF file", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(context, if (isArabic) "نوع الملف غير مدعوم" else "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val launchGalleryImport = {
        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    // System camera result: same unified pipeline as the in-app camera and the gallery
    // (EXIF, bounded decode, detection chain, perspective, default filter). The previous code
    // detected a quad, ignored it, applied MAGIC to the full photo and decoded it at full size.
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempCameraFile
        tempCameraFile = null
        if (!success || file == null || !file.exists()) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            isProcessingCapture = true
            try {
                val page = DocumentPipeline.processFile(context, file.absolutePath, prefix = "scan")
                if (page != null) {
                    onPagesCaptured(listOf(Pair(page.rawPath, page.processedPath)))
                    onNavigateToEditSession("CAMERA", 0L)
                } else {
                    Toast.makeText(context, if (isArabic) "تعذر معالجة الصورة" else "Could not process the photo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, if (isArabic) "تعذر معالجة الصورة" else "Could not process the photo", Toast.LENGTH_SHORT).show()
            } finally {
                file.delete()
                isProcessingCapture = false
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
                val ids = selectedDocIds.toList()
                TopAppBar(
                    title = { Text(if (isArabic) "${selectedDocIds.size} محدد" else "${selectedDocIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.txt_cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedDocIds = if (selectedDocIds.size == uiState.documents.size) emptySet() else uiState.documents.map { it.id }.toSet()
                            if (selectedDocIds.isEmpty()) selectionMode = false
                        }) { Icon(Icons.Default.DoneAll, contentDescription = if (isArabic) "تحديد الكل" else "Select all") }
                        IconButton(enabled = ids.isNotEmpty(), onClick = { listViewModel.shareDocumentsAsPdf(context, ids) }) {
                            Icon(Icons.Default.Share, contentDescription = if (isArabic) "مشاركة كـ PDF" else "Share as PDF")
                        }
                        IconButton(enabled = ids.isNotEmpty(), onClick = { listViewModel.setFavorite(ids) }) {
                            Icon(Icons.Default.Star, contentDescription = if (isArabic) "المفضلة" else "Favorite", tint = GoldBase)
                        }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.txt_options)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "تصدير PDF…" else "Export PDF…") },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                    enabled = ids.isNotEmpty(),
                                    onClick = { more = false; exportDocIds = ids; showExportPdfDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "نقل إلى مجلد" else "Move to folder") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                    enabled = ids.isNotEmpty(),
                                    onClick = { more = false; moveDocIds = ids; showMoveDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "إعادة تسمية" else "Rename") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    enabled = ids.isNotEmpty(),
                                    onClick = { more = false; renameDocIds = ids; showRenameDialog = true }
                                )
                                if (ids.size >= 2) {
                                    DropdownMenuItem(
                                        text = { Text(if (isArabic) "دمج في مستند واحد" else "Merge into one document") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, null) },
                                        onClick = { more = false; listViewModel.mergeDocuments(ids); exitSelection() }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "نقل إلى سلة المحذوفات" else "Move to Trash", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = ids.isNotEmpty(),
                                    onClick = { more = false; listViewModel.moveToTrashWithUndo(ids); exitSelection() }
                                )
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GoldBase)
                            Text(
                                text = if (isArabic) "${uiState.documents.size} مستند" else "${uiState.documents.size} documents",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = if (isArabic) "بحث" else "Search")
                        }
                        IconButton(onClick = { listViewModel.toggleFavoritesFilter() }) {
                            Icon(
                                if (uiState.showFavoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isArabic) "المفضلة فقط" else "Favorites only",
                                tint = if (uiState.showFavoritesOnly) GoldBase else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.txt_options)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "ترتيب" else "Sort") },
                                    leadingIcon = { Icon(Icons.Default.Sort, null) },
                                    onClick = { more = false; showSortSheet = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isArabic) "تحديد مستندات" else "Select documents") },
                                    leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                    enabled = uiState.documents.isNotEmpty(),
                                    onClick = { more = false; selectionMode = true }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isArabic) "سلة المحذوفات (${uiState.trashDocuments.size})"
                                            else "Trash (${uiState.trashDocuments.size})"
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                    onClick = { more = false; showTrashSheet = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.desc_settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { more = false; onNavigateToSettings() }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Column(horizontalAlignment = Alignment.End) {
                    // One "Import" entry with explicit choices (previously two unlabeled icons).
                    Box(modifier = Modifier.padding(bottom = 12.dp)) {
                        SmallFloatingActionButton(
                            onClick = { showImportMenu = true },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) { Icon(Icons.Default.FileUpload, contentDescription = if (isArabic) "استيراد" else "Import") }
                        DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (isArabic) "صور من المعرض" else "Photos from gallery") },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                onClick = { showImportMenu = false; launchGalleryImport() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isArabic) "ملفات (صور أو PDF)" else "Files (images or PDF)") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    showImportMenu = false
                                    filesPickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                                }
                            )
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { onNavigateToScan("DOCUMENT") },
                        containerColor = Emerald400,
                        contentColor = Color.Black,
                        shape = RoundedCornerShape(16.dp),
                        icon = { Icon(Icons.Default.CameraAlt, null) },
                        text = { Text(if (isArabic) "مسح مستند" else "Scan", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showSearch) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { listViewModel.onSearchQueryChanged(it) },
                    placeholder = { Text(if (isArabic) "ابحث في العناوين ونص المستندات..." else "Search titles and document text...") },
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
                    onCreateFolderClick = { showNewFolderDialog = true },
                    // Previously not connected: the chip menu's Rename / Delete did nothing.
                    onRenameFolder = { old, new -> listViewModel.renameFolder(old, new) },
                    onDeleteFolder = { folderToDelete = it }
                )
                CategoryChipsRow(
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = { listViewModel.filterByCategory(it) }
                )
            }
            val docs = uiState.documents
            if (docs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        val filtered = uiState.searchQuery.isNotBlank() || uiState.showFavoritesOnly ||
                            uiState.selectedFolder != "ALL" || uiState.selectedCategory != com.example.data.model.DocumentCategory.ALL
                        Text(
                            if (filtered) stringResource(R.string.txt_no_matching) else stringResource(R.string.txt_no_documents),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!filtered) {
                            Text(
                                stringResource(R.string.txt_no_documents_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(docs, key = { it.id }) { doc ->
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
                                selectedDocIds = selectedDocIds + doc.id
                            },
                            onDeleteClick = { listViewModel.moveToTrashWithUndo(listOf(doc.id)) },
                            onRenameClick = { renameDocIds = listOf(doc.id); showRenameDialog = true },
                            onExportClick = { exportDocIds = listOf(doc.id); showExportPdfDialog = true },
                            onShareClick = { listViewModel.shareDocumentsAsPdf(context, listOf(doc.id)) },
                            onMoveClick = { moveDocIds = listOf(doc.id); showMoveDialog = true },
                            onFavoriteClick = { listViewModel.toggleFavorite(doc.id) }
                        )
                    }
                }
            }
        }
        if (isProcessingCapture) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Emerald400)
            }
        }
        }
    }
    // Dialogs
    if (showNewFolderDialog) {
        NewFolderDialog(
            show = showNewFolderDialog,
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name -> listViewModel.createFolder(name) }
        )
    }
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(if (isArabic) "حذف المجلد \"$folder\"؟" else "Delete folder \"$folder\"?") },
            text = {
                Text(
                    if (isArabic) "لن تُحذف المستندات، بل تُنقل إلى المجلد الافتراضي."
                    else "Documents are not deleted; they are moved back to the default folder."
                )
            },
            confirmButton = {
                TextButton(onClick = { listViewModel.deleteFolder(folder); folderToDelete = null }) {
                    Text(if (isArabic) "حذف" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(if (isArabic) "إلغاء" else "Cancel") } }
        )
    }
    if (showMoveDialog) {
        var newFolder by remember { mutableStateOf("") }
        val targets = listOf("Default") + uiState.folders.filter { it != "Default" && it != "ALL" }
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(if (isArabic) "نقل ${moveDocIds.size} مستند إلى" else "Move ${moveDocIds.size} document(s) to") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    targets.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    listViewModel.moveDocumentsToFolder(moveDocIds, folder)
                                    showMoveDialog = false
                                    selectionMode = false
                                    selectedDocIds = emptySet()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (folder == "Default") (if (isArabic) "المجلد الافتراضي" else "Default") else folder)
                        }
                    }
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text(if (isArabic) "أو مجلد جديد" else "Or a new folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newFolder.isNotBlank(),
                    onClick = {
                        listViewModel.moveDocumentsToFolder(moveDocIds, newFolder.trim())
                        showMoveDialog = false
                        selectionMode = false
                        selectedDocIds = emptySet()
                    }
                ) { Text(if (isArabic) "نقل" else "Move") }
            },
            dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text(if (isArabic) "إلغاء" else "Cancel") } }
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
            selectedDocuments = uiState.documents.filter { exportDocIds.contains(it.id) },
            totalPageCount = uiState.documents.filter { exportDocIds.contains(it.id) }.sumOf { it.pageCount },
            isExporting = uiState.isLoading,
            onDismiss = { showExportPdfDialog = false },
            onExportAction = { config, action ->
                pendingExportConfig = config
                if (action == ExportPdfAction.SAVE_AS) {
                    createPdfDocumentLauncher.launch(com.example.engine.pdf.PdfEngine.safeFileName(config.title) + ".pdf")
                } else {
                    // Every action gives visible feedback (Preview previously produced nothing on screen).
                    listViewModel.exportDocumentsAsPdf(
                        context, exportDocIds, config, action,
                        onSuccess = { file, _, _ ->
                            showExportPdfDialog = false
                            exitSelection()
                            when (action) {
                                ExportPdfAction.PREVIEW -> previewPdfFile = file
                                ExportPdfAction.SAVE_TO_DOWNLOADS -> Toast.makeText(
                                    context, if (isArabic) "تم الحفظ في التنزيلات/MS Scanner" else "Saved to Downloads/MS Scanner", Toast.LENGTH_LONG
                                ).show()
                                else -> Unit
                            }
                        }
                    )
                }
            }
        )
    }
    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(if (isArabic) "ترتيب المستندات" else "Sort documents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    com.example.ui.viewmodel.SortOrder.DATE_MODIFIED to (if (isArabic) "آخر تعديل" else "Last modified"),
                    com.example.ui.viewmodel.SortOrder.DATE_CREATED to (if (isArabic) "تاريخ الإنشاء" else "Date created"),
                    com.example.ui.viewmodel.SortOrder.NAME to (if (isArabic) "الاسم" else "Name"),
                    com.example.ui.viewmodel.SortOrder.SIZE to (if (isArabic) "الحجم" else "Size")
                ).forEach { (order, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { listViewModel.setSortMode(order); showSortSheet = false }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = uiState.sortMode == order, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        }
    }
    // Trash: restore or delete for good (previously there was no way to see or restore trashed documents).
    if (showTrashSheet) {
        ModalBottomSheet(onDismissRequest = { showTrashSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isArabic) "سلة المحذوفات" else "Trash",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.trashDocuments.isNotEmpty()) {
                        TextButton(onClick = { confirmEmptyTrash = true }) {
                            Text(stringResource(R.string.txt_empty_trash), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (uiState.trashDocuments.isEmpty()) {
                    Text(stringResource(R.string.txt_trash_is_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(uiState.trashDocuments.size, key = { uiState.trashDocuments[it].id }) { i ->
                            val d = uiState.trashDocuments[i]
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    Text(if (isArabic) "${d.pageCount} صفحة" else "${d.pageCount} pages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { listViewModel.restoreFromTrash(d.id) }) {
                                    Icon(Icons.Default.RestoreFromTrash, contentDescription = stringResource(R.string.desc_restore), tint = Emerald400)
                                }
                                IconButton(onClick = { listViewModel.deletePermanently(d.id) }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.desc_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(if (isArabic) "إفراغ سلة المحذوفات؟" else "Empty Trash?") },
            text = { Text(if (isArabic) "سيتم حذف ${uiState.trashDocuments.size} مستند نهائياً ولا يمكن التراجع." else "${uiState.trashDocuments.size} document(s) will be deleted permanently. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { listViewModel.emptyTrash(); confirmEmptyTrash = false }) {
                    Text(if (isArabic) "حذف نهائي" else "Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text(stringResource(R.string.txt_cancel)) } }
        )
    }
    previewPdfFile?.let { file ->
        PdfViewerOverlay(pdfFile = file, onDismiss = { previewPdfFile = null })
    }
}
