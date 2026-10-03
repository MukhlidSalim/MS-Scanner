package com.example.ui.screens.viewer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.CompressionPreset
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.model.PageSizePreset
import com.example.engine.cv.DocumentPipeline
import com.example.engine.ocr.DocumentAnalysisWorker
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.components.MergePagesDialog
import com.example.ui.components.PageActionsBottomSheet
import com.example.ui.components.PdfViewerOverlay
import com.example.ui.components.ScanActionButton
import com.example.ui.screens.viewer.components.PdfSettingsDialog
import com.example.ui.screens.viewer.components.SaveShareSheet
import com.example.ui.screens.viewer.components.SelectionActionBar
import com.example.ui.theme.Emerald400
import com.example.ui.theme.GoldBase
import com.example.ui.theme.StudioCanvasBg
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.EditSessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the pre-export dialog ([ExportOptionsDialog]) should do once the user confirms name + size. */
private enum class PendingExportKind { SAVE_PDF, SAVE_PDF_AS, SAVE_IMAGES, SHARE_PDF, SHARE_IMAGES }

/**
 * Saved document screen.
 *
 * Save & Share is ONE sheet ("Save & Share"), used for the whole document or for the selected pages:
 *   Save as PDF (Downloads) · Save PDF as… (choose location) · Save as Images (Gallery)
 *   Share PDF · Share as Images · Print / Save via print dialog · PDF settings (advanced)
 * Every export runs off the main thread, shows one progress overlay and can't be started twice.
 * The sheet opens automatically right after a new scan is saved.
 *
 * ADDED: every Save/Share entry point now opens [ExportOptionsDialog] first, letting the user rename the
 * output file and choose a smaller file size (compression level) before the PDF/images are produced -
 * this was previously only available deep inside "PDF settings & export…", not from the main Save & Share
 * flow that most shares actually go through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    docId: Long,
    viewModel: EditSessionViewModel,
    onImportedUris: (List<Uri>) -> Unit = {},
    onNavigateBack: () -> Unit,
    onNavigateToScan: (Long, Long) -> Unit, // docId, replacePageId
    onNavigateToCrop: (Long, Long) -> Unit,
    onNavigateToOcr: (Long, Long) -> Unit,
    onNavigateToAnnotate: (Long, Long) -> Unit,
    onNavigateToEditSession: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val doc = uiState.activeDocument?.takeIf { it.id == docId }
    val pages = if (uiState.activeDocument?.id == docId) uiState.activePages else emptyList()
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var showShareSheet by remember { mutableStateOf(false) }
    var shareScope by remember { mutableStateOf(com.example.ui.screens.viewer.components.ShareScope.DOCUMENT) }
    var showDocInfo by remember { mutableStateOf(false) }
    // Background OCR / classification progress (WorkManager). Never blocks viewing, saving or sharing.
    val analysisFlow = remember(docId) { DocumentAnalysisWorker.observeProgress(context, docId) }
    val analysisProgress by analysisFlow.collectAsState(initial = null)
    LaunchedEffect(docId) {
        viewModel.loadDocument(docId)
        if (viewModel.consumeShareSheetRequest(docId)) showShareSheet = true
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPageIds by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (pagerState.currentPage in pages.indices) viewModel.selectPageIndex(pagerState.currentPage)
    }
    // Drop selections that no longer exist (page deleted / merged).
    LaunchedEffect(pages) {
        val ids = pages.map { it.id }.toSet()
        if (selectedPageIds.any { it !in ids }) selectedPageIds = selectedPageIds.intersect(ids)
    }
    var isGridView by remember { mutableStateOf(false) }
    var showPdfExportDialog by remember { mutableStateOf(false) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var pageForActions by remember { mutableStateOf<PageEntity?>(null) }
    var pageForActionsIndex by remember { mutableStateOf(0) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var initialMergePageIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var targetPageForReplace by remember { mutableStateOf<PageEntity?>(null) }
    var pendingPdfForSaveAs by remember { mutableStateOf<File?>(null) }
    var pendingImagesForFolder by remember { mutableStateOf<List<String>>(emptyList()) }
    val docTitle = doc?.title?.ifBlank { null } ?: "Document"
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun exitSelection() {
        selectionMode = false
        selectedPageIds = emptySet()
    }
    /** Pages targeted by Save / Share / Print: the selection when active, otherwise the whole document. */
    fun targetPages(): List<PageEntity> =
        if (selectionMode && selectedPageIds.isNotEmpty()) pages.filter { it.id in selectedPageIds } else pages
    fun imagePathOf(p: PageEntity): String =
        if (p.processedImagePath.isNotBlank() && File(p.processedImagePath).exists()) p.processedImagePath else p.rawImagePath

    // ---- Export options (name + size) shown before any Save/Share action runs ----
    var exportDialogKind by remember { mutableStateOf<PendingExportKind?>(null) }
    var exportDialogTargets by remember { mutableStateOf<List<PageEntity>>(emptyList()) }

    fun quickConfig(title: String = docTitle, compression: CompressionPreset = uiState.defaultPdfCompression) = PdfExportConfig(
        title = title,
        pageSize = uiState.defaultPdfPageSize,
        compression = compression
    )
    /** Runs one export at a time with a progress overlay; errors become a message, never a crash. */
    fun runBusy(message: String, block: suspend () -> Unit) {
        if (busyMessage != null) return
        coroutineScope.launch {
            busyMessage = message
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                toast(t("Operation failed: ", "فشلت العملية: ") + (e.localizedMessage ?: e.javaClass.simpleName))
            } finally {
                busyMessage = null
            }
        }
    }
    suspend fun buildPdf(targets: List<PageEntity>, title: String, compression: CompressionPreset): File =
        PdfEngine.generatePdf(context, targets.map { Pair(imagePathOf(it), it.ocrText) }, quickConfig(title, compression))
    // ---- System pickers (no storage permission needed) ----
    val createPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val file = pendingPdfForSaveAs
        pendingPdfForSaveAs = null
        if (uri != null && file != null) {
            runBusy(t("Saving PDF…", "جاري حفظ PDF…")) {
                val ok = withContext(Dispatchers.IO) { PdfEngine.copyPdfToUri(context, file, uri) }
                toast(if (ok) t("PDF saved", "تم حفظ ملف PDF") else t("Could not save the PDF", "تعذر حفظ ملف PDF"))
            }
        }
    }
    val pickFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val paths = pendingImagesForFolder
        pendingImagesForFolder = emptyList()
        if (treeUri != null && paths.isNotEmpty()) {
            runBusy(t("Saving images…", "جاري حفظ الصور…")) {
                val saved = PdfEngine.saveImagesToTree(context, treeUri, paths, docTitle)
                toast(t("$saved image(s) saved", "تم حفظ $saved صورة"))
            }
        }
    }
    // ---- Save & Share actions (name + compression already chosen by ExportOptionsDialog) ----
    fun savePdf(targets: List<PageEntity>, title: String, compression: CompressionPreset) {
        if (targets.isEmpty()) return
        runBusy(t("Creating PDF…", "جاري إنشاء PDF…")) {
            val pdf = buildPdf(targets, title, compression)
            val uri = PdfEngine.savePdfToDownloads(context, pdf, title)
            if (uri != null) {
                toast(t("Saved to Downloads/MS Scanner", "تم الحفظ في التنزيلات/MS Scanner"))
            } else {
                pendingPdfForSaveAs = pdf
                createPdfLauncher.launch("${PdfEngine.safeFileName(title)}.pdf")
            }
        }
    }
    fun savePdfAs(targets: List<PageEntity>, title: String, compression: CompressionPreset) {
        if (targets.isEmpty()) return
        runBusy(t("Creating PDF…", "جاري إنشاء PDF…")) {
            pendingPdfForSaveAs = buildPdf(targets, title, compression)
            createPdfLauncher.launch("${PdfEngine.safeFileName(title)}.pdf")
        }
    }
    fun saveImages(targets: List<PageEntity>, title: String) {
        if (targets.isEmpty()) return
        val paths = targets.map { imagePathOf(it) }
        runBusy(t("Saving images…", "جاري حفظ الصور…")) {
            val saved = PdfEngine.saveImagesToGallery(context, paths, title)
            if (saved < 0) {
                pendingImagesForFolder = paths
                pickFolderLauncher.launch(null)
            } else {
                toast(t("$saved image(s) saved to Pictures/MS Scanner", "تم حفظ $saved صورة في الصور/MS Scanner"))
            }
        }
    }
    fun sharePdf(targets: List<PageEntity>, title: String, compression: CompressionPreset) {
        if (targets.isEmpty()) return
        runBusy(t("Creating PDF…", "جاري إنشاء PDF…")) {
            val pdf = buildPdf(targets, title, compression)
            if (!PdfEngine.sharePdf(context, pdf, t("Share PDF", "مشاركة PDF"))) toast(t("Nothing to share with", "لا يوجد تطبيق للمشاركة"))
        }
    }
    fun shareImages(targets: List<PageEntity>, title: String) {
        if (targets.isEmpty()) return
        runBusy(t("Preparing images…", "جاري تجهيز الصور…")) {
            val files = PdfEngine.prepareImagesForShare(context, targets.map { imagePathOf(it) }, title)
            if (!PdfEngine.shareFiles(context, files, "image/jpeg", t("Share images", "مشاركة الصور"))) {
                toast(t("Nothing to share", "لا يوجد ما يمكن مشاركته"))
            }
        }
    }
    /** Opens the rename/size dialog, then runs [kind] with whatever the user confirms. */
    fun requestExport(kind: PendingExportKind, targets: List<PageEntity>) {
        exportDialogKind = kind
        exportDialogTargets = targets
    }
    fun print(targets: List<PageEntity>) {
        if (targets.isEmpty()) return
        PdfEngine.printScannedDocuments(context, docTitle, targets.map { imagePathOf(it) })
    }
    fun deletePages(ids: List<Long>) {
        if (ids.isEmpty()) return
        exitSelection()
        if (ids.size >= pages.size) {
            // Deleting every page removes the document (to Trash) instead of leaving an empty document.
            viewModel.moveDocumentToTrash(docId) { onNavigateBack() }
        } else {
            ids.forEach { viewModel.deletePageById(it) }
        }
    }
    // ---- Pickers for adding / replacing pages (same pipeline as the camera) ----
    val addPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportedUris(uris)
            onNavigateToEditSession("IMPORT", docId)
        }
    }
    val replacePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val pageToReplace = targetPageForReplace ?: pages.getOrNull(pagerState.currentPage)
        targetPageForReplace = null
        if (uri != null && pageToReplace != null) {
            runBusy(t("Processing image…", "جاري معالجة الصورة…")) {
                // EXIF, bounded decode, edge detection, perspective and filter — never on the main thread.
                val page = DocumentPipeline.processUri(context, uri, prefix = "replace")
                if (page != null) {
                    viewModel.replacePage(pageToReplace.id, page.rawPath, page.processedPath)
                    toast(
                        if (page.detectionStatus == com.example.engine.cv.DetectionStatus.NOT_FOUND)
                            t("Page replaced — edges not detected, use Edit to crop", "تم الاستبدال — لم تُكتشف الحواف، استخدم تعديل للقص")
                        else t("Page replaced", "تم استبدال الصفحة")
                    )
                } else {
                    toast(t("Could not read the image", "تعذر قراءة الصورة"))
                }
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.ShowToast -> toast(event.message)
                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.Error -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                else -> {}
            }
        }
    }
    BackHandler {
        when {
            selectionMode -> exitSelection()
            isGridView -> isGridView = false
            else -> onNavigateBack()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(
                            text = t("${selectedPageIds.size} Selected", "${selectedPageIds.size} محدد"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Column(modifier = Modifier.clickable {
                            renameInput = doc?.title ?: ""
                            showRenameDialog = true
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(docTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                            if (pages.isNotEmpty()) {
                                Text(
                                    text = t("Page ${pagerState.currentPage + 1} of ${pages.size}", "صفحة ${pagerState.currentPage + 1} من ${pages.size}"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.txt_cancel_selection))
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectedPageIds = if (selectedPageIds.size == pages.size) emptySet() else pages.map { it.id }.toSet()
                            if (selectedPageIds.isEmpty()) selectionMode = false
                        }) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = t("Select All", "تحديد الكل"),
                                tint = if (selectedPageIds.size == pages.size) Emerald400 else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (selectedPageIds.isNotEmpty()) {
                            IconButton(onClick = {
                                shareScope = com.example.ui.screens.viewer.components.ShareScope.SELECTED
                                showShareSheet = true
                            }) {
                                Icon(Icons.Default.Share, contentDescription = t("Save & Share Selected", "حفظ ومشاركة المحدد"), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showDeleteSelectedConfirmDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = t("Delete Selected", "حذف المحدد"), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        // Primary action: one entry point for every Save / Share / Export option.
                        IconButton(onClick = { doc?.let { viewModel.updateDocumentMetadata(it.id, favorite = !it.isFavorite) } }) {
                            Icon(
                                if (doc?.isFavorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = t("Favorite", "المفضلة"),
                                tint = if (doc?.isFavorite == true) GoldBase else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                shareScope = com.example.ui.screens.viewer.components.ShareScope.DOCUMENT
                                showShareSheet = true
                            },
                            enabled = pages.isNotEmpty(),
                            modifier = Modifier.testTag("save_share_btn")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = t("Save & Share", "حفظ ومشاركة"), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onNavigateToEditSession("EXISTING", docId) }, enabled = pages.isNotEmpty()) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = t("Edit all pages", "تعديل كل الصفحات"), tint = Emerald400)
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
                                DropdownMenuItem(
                                    text = { Text(t("Document info (title, category, tags)", "معلومات المستند (الاسم، التصنيف، الوسوم)")) },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { showOverflowMenu = false; showDocInfo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("Select pages", "تحديد صفحات")) },
                                    leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        selectionMode = true
                                        isGridView = true
                                        pages.getOrNull(pagerState.currentPage)?.let { selectedPageIds = setOf(it.id) }
                                    }
                                )
                                if (pages.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text(t("Reorder pages", "إعادة ترتيب الصفحات")) },
                                        leadingIcon = { Icon(Icons.Default.Reorder, null) },
                                        onClick = { showOverflowMenu = false; showReorderDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("Merge into single page", "دمج الصور في صفحة واحدة")) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, null, tint = Emerald400) },
                                        onClick = {
                                            showOverflowMenu = false
                                            initialMergePageIds = listOfNotNull(pages.getOrNull(pagerState.currentPage)?.id)
                                            showMergeDialog = true
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(t("Replace page (Camera)", "استبدال الصفحة (كاميرا)")) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        pages.getOrNull(pagerState.currentPage)?.let { onNavigateToScan(docId, it.id) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("Replace page (Gallery)", "استبدال الصفحة (المعرض)")) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        targetPageForReplace = pages.getOrNull(pagerState.currentPage)
                                        replacePhotoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(t("PDF settings & export…", "إعدادات وتصدير PDF…")) },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = { showOverflowMenu = false; showPdfExportDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.txt_print)) },
                                    leadingIcon = { Icon(Icons.Default.Print, null) },
                                    onClick = { showOverflowMenu = false; print(pages) }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (!selectionMode && busyMessage == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScanActionButton(
                        onClick = { addPhotoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        icon = Icons.Default.AddPhotoAlternate,
                        contentDescription = t("Import pages", "استيراد صفحات"),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ScanActionButton(
                        onClick = { onNavigateToScan(docId, 0L) },
                        icon = Icons.Default.AddAPhoto,
                        contentDescription = stringResource(R.string.desc_add_page),
                        modifier = Modifier.padding(bottom = 8.dp)
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
                        shareScope = com.example.ui.screens.viewer.components.ShareScope.SELECTED
                        showShareSheet = true
                    },
                    onExportPdf = { showPdfExportDialog = true },
                    onPrint = { print(targetPages()) },
                    onDuplicate = {
                        selectedPageIds.forEach { viewModel.duplicatePage(it) }
                        exitSelection()
                    },
                    onDelete = { showDeleteSelectedConfirmDialog = true }
                )
            } else if (!isGridView && pages.size > 1) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.id }) { index, p ->
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
                                    .clickable { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                            ) {
                                AsyncImage(
                                    model = File(p.processedImagePath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                ) {
                                    Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(StudioCanvasBg)) {
            Column(modifier = Modifier.fillMaxSize()) {
                analysisProgress?.let { (done, total) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Emerald400)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (total > 0) t("Extracting text in background… $done/$total", "جاري استخراج النص في الخلفية… $done/$total")
                            else t("Extracting text in background…", "جاري استخراج النص في الخلفية…"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Quality banner with one-tap fix
                val quality = uiState.currentQualityReport
                if (quality != null && !isGridView && (quality.isBlurry || quality.isDark || quality.isLowContrast)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF451A03),
                        border = BorderStroke(0.5.dp, WarningAmber.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isArabic) quality.statusTextAr else quality.statusTextEn,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.applyFilterToActivePage(FilterType.AUTO) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(t("Auto Fix", "إصلاح تلقائي"), color = Emerald400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // Per-page tools
                if (!isGridView && pages.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        val current = pages.getOrNull(pagerState.currentPage)
                        PageTool(Icons.Default.ColorLens, stringResource(R.string.txt_filter)) { if (current != null) showFilterSheet = true }
                        PageTool(Icons.Default.Crop, t("Edit", "تعديل")) { current?.let { onNavigateToCrop(docId, it.id) } }
                        PageTool(Icons.Default.TextFields, stringResource(R.string.txt_ocr)) { current?.let { onNavigateToOcr(docId, it.id) } }
                        PageTool(Icons.Default.Draw, stringResource(R.string.txt_sign)) { current?.let { onNavigateToAnnotate(docId, it.id) } }
                        PageTool(Icons.Default.MoreHoriz, t("More", "المزيد")) {
                            current?.let {
                                pageForActionsIndex = pagerState.currentPage
                                pageForActions = it
                            }
                        }
                    }
                }
                if (pages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        gridItemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
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
                                                    if (selectedPageIds.isEmpty()) selectionMode = false
                                                } else {
                                                    isGridView = false
                                                    coroutineScope.launch { pagerState.scrollToPage(index) }
                                                }
                                            },
                                            onLongPress = {
                                                selectionMode = true
                                                selectedPageIds = selectedPageIds + page.id
                                            }
                                        )
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(
                                    width = if (isPageSelected) 3.dp else 1.dp,
                                    color = if (isPageSelected) Emerald400 else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
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
                                            if (isPageSelected) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
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
                        key = { pages.getOrNull(it)?.id ?: it },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(14.dp)
                    ) { index ->
                        val pageItem = pages.getOrNull(index) ?: return@HorizontalPager
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                .pointerInput(pageItem.id) {
                                    detectTapGestures(
                                        onLongPress = {
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
            }
            // Export progress overlay (blocks double taps; everything runs off the main thread).
            busyMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Emerald400, strokeWidth = 2.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(msg, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    // ================================================================================== sheets & dialogs
    if (showShareSheet) {
        val selectedInOrder = pages.filter { it.id in selectedPageIds }
        val currentPage = pages.getOrNull(pagerState.currentPage)
        val effectiveScope = when {
            shareScope == com.example.ui.screens.viewer.components.ShareScope.SELECTED && selectedInOrder.isEmpty() ->
                com.example.ui.screens.viewer.components.ShareScope.DOCUMENT
            shareScope == com.example.ui.screens.viewer.components.ShareScope.CURRENT_PAGE && currentPage == null ->
                com.example.ui.screens.viewer.components.ShareScope.DOCUMENT
            else -> shareScope
        }
        val targets = when (effectiveScope) {
            com.example.ui.screens.viewer.components.ShareScope.DOCUMENT -> pages
            com.example.ui.screens.viewer.components.ShareScope.CURRENT_PAGE -> listOfNotNull(currentPage)
            com.example.ui.screens.viewer.components.ShareScope.SELECTED -> selectedInOrder // e.g. 1 + 3 + 5 -> one PDF
        }
        SaveShareSheet(
            isArabic = isArabic,
            pageCount = targets.size,
            isSelection = effectiveScope == com.example.ui.screens.viewer.components.ShareScope.SELECTED,
            onDismiss = { showShareSheet = false },
            scope = effectiveScope,
            documentPageCount = pages.size,
            currentPageNumber = pagerState.currentPage + 1,
            selectedCount = selectedInOrder.size,
            onScopeChange = { shareScope = it },
            onSelectPages = {
                showShareSheet = false
                selectionMode = true
                isGridView = true
                if (selectedPageIds.isEmpty()) pages.getOrNull(pagerState.currentPage)?.let { selectedPageIds = setOf(it.id) }
                toast(t("Tap pages to select them, then tap Share", "اضغط على الصفحات لتحديدها ثم اضغط مشاركة"))
            },
            onSavePdf = { showShareSheet = false; requestExport(PendingExportKind.SAVE_PDF, targets) },
            onSavePdfAs = { showShareSheet = false; requestExport(PendingExportKind.SAVE_PDF_AS, targets) },
            onSaveImages = { showShareSheet = false; requestExport(PendingExportKind.SAVE_IMAGES, targets) },
            onSharePdf = { showShareSheet = false; requestExport(PendingExportKind.SHARE_PDF, targets) },
            onShareImages = { showShareSheet = false; requestExport(PendingExportKind.SHARE_IMAGES, targets) },
            onPrint = { showShareSheet = false; print(targets) },
            onPdfSettings = { showShareSheet = false; showPdfExportDialog = true }
        )
    }
    // Rename + file-size (compression) step shown before the chosen Save/Share action actually runs.
    exportDialogKind?.let { kind ->
        ExportOptionsDialog(
            isArabic = isArabic,
            defaultTitle = docTitle,
            defaultCompression = uiState.defaultPdfCompression,
            showCompression = kind != PendingExportKind.SAVE_IMAGES && kind != PendingExportKind.SHARE_IMAGES,
            pageCount = exportDialogTargets.size,
            onDismiss = { exportDialogKind = null },
            onConfirm = { title, compression ->
                val targets = exportDialogTargets
                exportDialogKind = null
                when (kind) {
                    PendingExportKind.SAVE_PDF -> savePdf(targets, title, compression)
                    PendingExportKind.SAVE_PDF_AS -> savePdfAs(targets, title, compression)
                    PendingExportKind.SAVE_IMAGES -> saveImages(targets, title)
                    PendingExportKind.SHARE_PDF -> sharePdf(targets, title, compression)
                    PendingExportKind.SHARE_IMAGES -> shareImages(targets, title)
                }
            }
        )
    }
    if (showDeleteSelectedConfirmDialog) {
        val count = selectedPageIds.size
        val deletesAll = count >= pages.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text(t("Delete $count page(s)?", "حذف $count صفحة؟"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (deletesAll) t("All pages are selected: the document will be moved to Trash.", "كل الصفحات محددة: سيتم نقل المستند إلى سلة المحذوفات.")
                    else t("This action cannot be undone.", "لا يمكن التراجع عن هذا الإجراء.")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedPageIds.toList()
                        showDeleteSelectedConfirmDialog = false
                        deletePages(ids)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(t("Delete", "حذف"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirmDialog = false }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
    if (showReorderDialog) {
        ReorderPagesScreen(
            pages = pages,
            onSave = { newOrder ->
                viewModel.updatePagesOrder(newOrder)
                showReorderDialog = false
            },
            onCancel = { showReorderDialog = false }
        )
    }
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
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank() && doc != null) viewModel.renameDocument(doc.id, renameInput.trim())
                        showRenameDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val activeFilterName = pages.getOrNull(pagerState.currentPage)?.filterType
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.txt_document_filters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterType.values().forEach { filter ->
                        val isSelected = filter.name == activeFilterName
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.applyFilterToActivePage(filter)
                                showFilterSheet = false
                            },
                            label = { Text(if (isArabic) filter.displayNameAr else filter.displayNameEn) }
                        )
                    }
                }
                if (pages.size > 1) {
                    Button(
                        onClick = {
                            val active = runCatching { FilterType.valueOf(activeFilterName ?: "") }.getOrDefault(FilterType.AUTO)
                            viewModel.applyFilterToAllPages(docId, active)
                            showFilterSheet = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.txt_apply_to_all_pages), fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (showPdfExportDialog) {
        PdfSettingsDialog(
            isArabic = isArabic,
            pageCount = targetPages().size,
            initialSize = uiState.defaultPdfPageSize,
            initialCompression = uiState.defaultPdfCompression,
            isExporting = uiState.isExportingPdf,
            onDismiss = { showPdfExportDialog = false },
            onExport = { size, compression, includeOcr, includeNumbers, watermark, password ->
                val config = PdfExportConfig(
                    title = docTitle,
                    pageSize = size,
                    compression = compression,
                    includeSearchableText = includeOcr,
                    includePageNumbers = includeNumbers,
                    watermarkText = watermark,
                    password = password
                )
                val ids = if (selectionMode && selectedPageIds.isNotEmpty()) selectedPageIds else null
                viewModel.exportDocumentToPdf(config, ids) { generatedPdf ->
                    showPdfExportDialog = false
                    exitSelection()
                    if (password != null) {
                        // The in-app viewer (PdfRenderer) cannot open encrypted files: go straight to sharing.
                        PdfEngine.sharePdf(context, generatedPdf, t("Share protected PDF", "مشاركة PDF محمي"))
                    } else {
                        previewPdfFile = generatedPdf
                    }
                }
            },
            onPrint = {
                showPdfExportDialog = false
                print(targetPages())
            }
        )
    }
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
            onEditCropClick = { pageForActions = null; onNavigateToCrop(docId, targetPage.id) },
            onOcrClick = { pageForActions = null; onNavigateToOcr(docId, targetPage.id) },
            onAnnotateClick = { pageForActions = null; onNavigateToAnnotate(docId, targetPage.id) },
            onRotateClick = { pageForActions = null; viewModel.rotatePage(targetPage.id) },
            onDuplicateClick = { pageForActions = null; viewModel.duplicatePage(targetPage.id) },
            onReplaceClick = {
                pageForActions = null
                targetPageForReplace = targetPage
                replacePhotoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPrintClick = { pageForActions = null; print(listOf(targetPage)) },
            onShareClick = { pageForActions = null; shareImages(listOf(targetPage), docTitle) },
            onExportDocxClick = {
                pageForActions = null
                if (targetPage.ocrText.isBlank()) {
                    toast(t("No text yet — run OCR first", "لا يوجد نص بعد — شغّل استخراج النص أولاً"))
                } else runBusy(t("Exporting…", "جاري التصدير…")) {
                    val file = PdfEngine.exportDocx(context, "${docTitle}_p${pageForActionsIndex + 1}", targetPage.ocrText)
                    PdfEngine.shareFiles(context, listOf(file), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                }
            },
            onExportTxtClick = {
                pageForActions = null
                if (targetPage.ocrText.isBlank()) {
                    toast(t("No text yet — run OCR first", "لا يوجد نص بعد — شغّل استخراج النص أولاً"))
                } else runBusy(t("Exporting…", "جاري التصدير…")) {
                    val file = PdfEngine.exportText(context, targetPage.ocrText, "${docTitle}_p${pageForActionsIndex + 1}", "txt")
                    PdfEngine.shareFiles(context, listOf(file), "text/plain")
                }
            },
            onExportPngClick = {
                pageForActions = null
                runBusy(t("Exporting…", "جاري التصدير…")) {
                    val file = PdfEngine.exportPng(context, imagePathOf(targetPage), "${docTitle}_p${pageForActionsIndex + 1}")
                    if (file != null) PdfEngine.shareFiles(context, listOf(file), "image/png")
                    else toast(t("Could not export PNG", "تعذر تصدير PNG"))
                }
            },
            onDeleteClick = {
                pageForActions = null
                // Always confirmed (page files are removed for good).
                selectedPageIds = setOf(targetPage.id)
                showDeleteSelectedConfirmDialog = true
            }
        )
    }
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
                    exitSelection()
                    toast(t("Images merged into a single page", "تم دمج الصور في صفحة واحدة"))
                }
            }
        )
    }
    if (showDocInfo && doc != null) {
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        var tags by remember(doc.id) { mutableStateOf(doc.tagsCsv) }
        var category by remember(doc.id) {
            mutableStateOf(runCatching { com.example.data.model.DocumentCategory.valueOf(doc.category) }
                .getOrDefault(com.example.data.model.DocumentCategory.OTHER))
        }
        AlertDialog(
            onDismissRequest = { showDocInfo = false },
            title = { Text(t("Document info", "معلومات المستند"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, singleLine = true,
                        label = { Text(t("Title", "الاسم")) }, modifier = Modifier.fillMaxWidth()
                    )
                    Text(t("Category", "التصنيف"), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.example.data.model.DocumentCategory.values()
                            .filter { it != com.example.data.model.DocumentCategory.ALL }
                            .forEach { c ->
                                FilterChip(
                                    selected = category == c,
                                    onClick = { category = c },
                                    label = { Text(if (isArabic) c.displayNameAr else c.displayNameEn) }
                                )
                            }
                    }
                    OutlinedTextField(
                        value = tags, onValueChange = { tags = it }, singleLine = true,
                        label = { Text(t("Tags (comma separated)", "الوسوم (مفصولة بفاصلة)")) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        t("${pages.size} page(s)", "${pages.size} صفحة") + " · " +
                            java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(doc.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateDocumentMetadata(
                        doc.id,
                        title = title.trim().ifBlank { doc.title },
                        category = category.name,
                        tagsCsv = tags.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
                    )
                    showDocInfo = false
                }) { Text(stringResource(R.string.txt_save)) }
            },
            dismissButton = { TextButton(onClick = { showDocInfo = false }) { Text(stringResource(R.string.txt_cancel)) } }
        )
    }
    previewPdfFile?.let { file ->
        PdfViewerOverlay(pdfFile = file, onDismiss = { previewPdfFile = null })
    }
}
@Composable
private fun PageTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = GoldBase)
            Text(label, style = MaterialTheme.typography.labelSmall, color = GoldBase, maxLines = 1)
        }
    }
}

/**
 * Lets the user rename the output file and pick a smaller size (compression) BEFORE a Save/Share action
 * actually runs. Addresses "share flow should expose filename and size control": previously this was only
 * reachable through the separate "PDF settings & export…" menu entry, not from the main Save/Share flow
 * that Save as PDF / Share PDF / Share images actually use.
 */
@Composable
private fun ExportOptionsDialog(
    isArabic: Boolean,
    defaultTitle: String,
    defaultCompression: CompressionPreset,
    showCompression: Boolean,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (title: String, compression: CompressionPreset) -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var name by remember { mutableStateOf(defaultTitle) }
    var compression by remember { mutableStateOf(defaultCompression) }
    val estimatedBytes = remember(pageCount, compression) { PdfEngine.estimatePdfSizeBytes(pageCount.coerceAtLeast(1), compression) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = { Text(t("Name & size", "الاسم والحجم"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(t("File name", "اسم الملف")) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (showCompression) {
                    Column {
                        Text(t("Reduce file size", "تقليل حجم الملف"), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompressionPreset.values().forEach { preset ->
                                FilterChip(
                                    selected = compression == preset,
                                    onClick = { compression = preset },
                                    label = { Text(preset.name) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t("Estimated size: ", "الحجم التقريبي: ") + PdfEngine.formatEstimatedSize(estimatedBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifBlank { defaultTitle }, compression) },
                shape = RoundedCornerShape(12.dp)
            ) { Text(t("Continue", "متابعة"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "إلغاء")) } }
    )
}
