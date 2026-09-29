package com.example.ui.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.model.PageStatus
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.QuadStore
import com.example.ui.theme.Emerald400
import com.example.ui.viewmodel.EditSessionViewModel
import com.example.ui.viewmodel.PendingPageEdit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A page as seen by the review UI, independent of whether it is pending (camera) or saved (existing). */
private data class ReviewPage(
    val rawPath: String,
    val processedPath: String,
    val rotation: Int,
    val filter: FilterType?,
    val entityId: Long,
    val storedQuadJson: String,
    val status: PageStatus = PageStatus.PROCESSED
)

private enum class ReviewStage { REVIEW, PREVIEW }
private enum class ToolPanel { NONE, FILTER, ADJUST }

/**
 * Review / edit / save screen.
 *
 * Flow (new scan):
 *  - Single page: result is shown immediately (already auto-cropped + enhanced). Tools are optional;
 *    "Save" writes the document in one step and opens it (share / export live there).
 *  - Multi-page: pages are reviewed one by one. "Save & Next" confirms the page and moves on
 *    automatically; after the last page the Document Preview is shown (add / delete / reorder / edit),
 *    then "Save" writes the whole document ONCE (no partial or duplicate documents).
 *
 * All edits are re-rendered from the RAW image through DocumentPipeline in the ViewModels, so the crop
 * is never lost and nothing heavy runs on the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSessionScreen(
    editViewModel: EditSessionViewModel,
    // Camera responsibility data/actions
    pagesPendingEdit: List<Pair<String, String>> = emptyList(),
    cameraIsLoading: Boolean = false,
    onUpdatePendingPage: (Int, String) -> Unit = { _, _ -> },
    onRotatePendingPage: (Int, Boolean) -> Unit = { _, _ -> },
    onAnalyzePending: () -> Unit = {},
    onCommitPending: (Long, () -> Unit) -> Unit = { _, _ -> },
    onImportPages: (String, String, (Long) -> Unit) -> Unit = { _, _, _ -> },
    onClearPending: () -> Unit = {},
    // List responsibility data
    selectedFolder: String = "Default",
    // Navigation
    sourceType: String, // "CAMERA", "IMPORT", "EXISTING"
    docId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToFinish: (Long) -> Unit,
    onNavigateToAnnotate: (Long, Long) -> Unit = { _, _ -> },
    // New review-flow actions (pending pages). Defaults keep older call sites compiling.
    pendingEdits: Map<String, PendingPageEdit> = emptyMap(),
    cameraIsProcessing: Boolean = false,
    cameraIsSaving: Boolean = false,
    cameraIsBackgroundProcessing: Boolean = false,
    onDeletePendingPage: (Int) -> Unit = {},
    onMovePendingPage: (Int, Int) -> Unit = { _, _ -> },
    onApplyPendingEdits: (indices: List<Int>, changeFilter: Boolean, filter: FilterType?, brightness: Float?, contrast: Float?) -> Unit = { _, _, _, _, _ -> },
    onCropPendingPage: (Int, CropEditorResult) -> Unit = { _, _ -> },
    onRetakePendingPage: ((Int) -> Unit)? = null,
    onAddPendingPage: (() -> Unit)? = null,
    // Page lifecycle / failure handling (pending pages)
    pageStatuses: Map<String, PageStatus> = emptyMap(),
    onRetryDetection: (Int) -> Unit = {},
    onAcceptPageAsIs: (Int) -> Unit = {},
    onPageReviewed: (Int) -> Unit = {}
) {
    val editUiState by editViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val isExisting = sourceType == "EXISTING"

    // ---- Existing document: load, then start an in-memory editing session ----
    LaunchedEffect(sourceType, docId) {
        if (isExisting && editUiState.activeDocument?.id != docId) editViewModel.loadDocument(docId)
    }
    LaunchedEffect(isExisting, editUiState.activePages, editUiState.activeDocument?.id) {
        if (isExisting && editUiState.activeDocument?.id == docId &&
            editUiState.activePages.isNotEmpty() && !editUiState.isEditingSession
        ) {
            editViewModel.startEditingSession(editUiState.activePages)
        }
    }

    // ---- Unified page model ----
    val pages: List<ReviewPage> = if (isExisting) {
        editUiState.editingSessionPages.map { p: PageEntity ->
            ReviewPage(
                rawPath = p.rawImagePath.ifBlank { p.processedImagePath },
                processedPath = p.processedImagePath,
                rotation = p.rotationDegrees,
                filter = runCatching { FilterType.valueOf(p.filterType) }.getOrNull()?.takeIf { it != FilterType.ORIGINAL },
                entityId = p.id,
                storedQuadJson = p.cropQuadJson
            )
        }
    } else {
        pagesPendingEdit.map { (raw, proc) ->
            val e = pendingEdits[raw] ?: PendingPageEdit()
            ReviewPage(raw, proc, e.rotation, e.filter, 0L, "", pageStatuses[raw] ?: PageStatus.PROCESSED)
        }
    }
    val isBusy = if (isExisting) editUiState.isLoading else cameraIsProcessing
    val isSaving = if (isExisting) editUiState.isSaving else cameraIsSaving
    val isMulti = pages.size > 1

    var stage by rememberSaveable { mutableStateOf(if (isExisting) ReviewStage.PREVIEW else ReviewStage.REVIEW) }
    var currentIndex by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && currentIndex > pages.lastIndex) currentIndex = pages.lastIndex
    }
    val safeIndex = currentIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    var panel by remember { mutableStateOf(ToolPanel.NONE) }
    var applyToAll by remember { mutableStateOf(false) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    var showCropEditor by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var showReviewWarning by remember { mutableStateOf(false) }
    val pagesNeedingReview = pages.count { it.status.needsAttention }
    var documentTitle by rememberSaveable {
        mutableStateOf(
            if (isExisting) "" else "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        editViewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.ShowToast -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    // ---- Actions ----
    fun targets(): List<Int> = if (applyToAll && isMulti) pages.indices.toList() else listOf(safeIndex)

    fun rotate(clockwise: Boolean) {
        if (pages.isEmpty() || isBusy) return
        hasChanges = true
        if (isExisting) editViewModel.rotateEditingSessionPage(safeIndex, clockwise)
        else onRotatePendingPage(safeIndex, clockwise)
    }

    fun applyFilter(filter: FilterType?) {
        if (pages.isEmpty() || isBusy) return
        hasChanges = true
        if (isExisting) editViewModel.applyEditsToSessionPages(targets(), true, filter, null, null)
        else onApplyPendingEdits(targets(), true, filter, null, null)
    }

    fun applyAdjust() {
        if (pages.isEmpty() || isBusy) return
        hasChanges = true
        if (isExisting) editViewModel.applyEditsToSessionPages(targets(), false, null, brightness, contrast)
        else onApplyPendingEdits(targets(), false, null, brightness, contrast)
        panel = ToolPanel.NONE
    }

    fun deletePage(index: Int) {
        hasChanges = true
        if (isExisting) editViewModel.deleteSessionPage(index) else onDeletePendingPage(index)
        if (currentIndex >= index && currentIndex > 0) currentIndex--
    }

    fun movePage(from: Int, to: Int) {
        if (to !in pages.indices) return
        hasChanges = true
        if (isExisting) editViewModel.moveSessionPage(from, to) else onMovePendingPage(from, to)
    }

    fun save(confirmedReview: Boolean = false) {
        if (isSaving || isBusy || pages.isEmpty()) return
        if (!isExisting && cameraIsBackgroundProcessing) return
        if (pages.any { it.status == PageStatus.PROCESSING }) return
        // Detection failures are never saved silently: the user explicitly continues or fixes them.
        if (!confirmedReview && pagesNeedingReview > 0) {
            showReviewWarning = true
            return
        }
        when {
            isExisting -> editViewModel.commitEditingSessionChanges { onNavigateToFinish(docId) }
            docId > 0L -> onCommitPending(docId) { onNavigateToFinish(docId) }
            else -> onImportPages(documentTitle.trim(), selectedFolder) { newId -> onNavigateToFinish(newId) }
        }
    }

    fun exit() {
        if (isExisting) editViewModel.endEditingSession() else onClearPending()
        onNavigateBack()
    }

    fun requestExit() {
        // Unsaved scanned pages are always worth confirming; existing docs only when changed.
        if ((!isExisting && pages.isNotEmpty()) || hasChanges) showDiscardDialog = true else exit()
    }

    BackHandler(enabled = !showCropEditor) {
        when {
            panel != ToolPanel.NONE -> panel = ToolPanel.NONE
            stage == ReviewStage.REVIEW && isExisting -> stage = ReviewStage.PREVIEW
            else -> requestExit()
        }
    }

    // ---- Dialogs ----
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(t("Discard changes?", "تجاهل التعديلات؟")) },
            text = {
                Text(
                    if (isExisting) t("Your changes will be lost.", "ستفقد التعديلات غير المحفوظة.")
                    else t("The scanned pages will be deleted.", "سيتم حذف الصفحات الممسوحة.")
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; exit() }) {
                    Text(t("Discard", "تجاهل"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text(t("Keep editing", "متابعة")) } }
        )
    }
    if (showReviewWarning) {
        AlertDialog(
            onDismissRequest = { showReviewWarning = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(t("Edges not detected", "لم يتم اكتشاف حواف المستند")) },
            text = {
                Text(
                    t(
                        "$pagesNeedingReview page(s) are kept as the full photo because the document edges were not found. Review them or save anyway.",
                        "$pagesNeedingReview صفحة محفوظة كصورة كاملة لأن حواف المستند لم تُكتشف. راجعها أو احفظ على أي حال."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showReviewWarning = false; save(confirmedReview = true) }) {
                    Text(t("Save anyway", "حفظ على أي حال"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReviewWarning = false
                    val first = pages.indexOfFirst { it.status.needsAttention }
                    if (first >= 0) { currentIndex = first; stage = ReviewStage.REVIEW }
                }) { Text(t("Review", "مراجعة")) }
            }
        )
    }
    pendingDeleteIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = null },
            title = { Text(t("Delete page ${idx + 1}?", "حذف الصفحة ${idx + 1}؟")) },
            confirmButton = {
                TextButton(onClick = { pendingDeleteIndex = null; deletePage(idx) }) {
                    Text(t("Delete", "حذف"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteIndex = null }) { Text(t("Cancel", "إلغاء")) } }
        )
    }

    // ---- Crop editor (optional tool) ----
    if (showCropEditor) {
        val page = pages.getOrNull(safeIndex)
        if (page == null || page.rawPath.isBlank()) {
            showCropEditor = false
        } else {
            val indexAtOpen = safeIndex
            var resultDelivered by remember(page.rawPath) { mutableStateOf(false) }
            DocumentCropEditorScreen(
                imagePath = page.rawPath,
                initialQuad = DocumentQuad.fromJsonOrNull(page.storedQuadJson) ?: QuadStore.load(page.rawPath),
                initialRotation = page.rotation,
                initialFilter = page.filter,
                onCropResult = { r ->
                    resultDelivered = true
                    hasChanges = true
                    if (isExisting) editViewModel.updateEditingSessionPageCrop(indexAtOpen, r)
                    else onCropPendingPage(indexAtOpen, r)
                },
                onCropped = { newPath ->
                    if (!resultDelivered) {
                        hasChanges = true
                        if (isExisting) editViewModel.updateEditingSessionPageProcessedImage(indexAtOpen, newPath)
                        else onUpdatePendingPage(indexAtOpen, newPath)
                    }
                    showCropEditor = false
                },
                onCancel = { showCropEditor = false }
            )
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (stage == ReviewStage.REVIEW && isExisting) stage = ReviewStage.PREVIEW else requestExit()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back", "رجوع")) }
                },
                title = {
                    Text(
                        text = when {
                            pages.isEmpty() -> t("Review", "مراجعة")
                            stage == ReviewStage.PREVIEW -> t("Document Preview (${pages.size})", "معاينة المستند (${pages.size})")
                            isMulti -> t("Page ${safeIndex + 1} of ${pages.size}", "صفحة ${safeIndex + 1} من ${pages.size}")
                            else -> t("Review", "مراجعة")
                        },
                        maxLines = 1
                    )
                },
                actions = {
                    if (stage == ReviewStage.REVIEW && isMulti) {
                        TextButton(onClick = { panel = ToolPanel.NONE; stage = ReviewStage.PREVIEW }) {
                            Text(t("All pages", "كل الصفحات"))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                pages.isEmpty() && (cameraIsLoading || (isExisting && !editUiState.isEditingSession)) -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Emerald400)
                        Spacer(Modifier.height(12.dp))
                        Text(t("Processing pages…", "جاري معالجة الصفحات…"))
                    }
                }
                pages.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("No pages", "لا توجد صفحات"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        if (onAddPendingPage != null && !isExisting) {
                            Button(onClick = onAddPendingPage) {
                                Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text(t("Add page", "إضافة صفحة"))
                            }
                        }
                        TextButton(onClick = { exit() }) { Text(t("Close", "إغلاق")) }
                    }
                }
                stage == ReviewStage.REVIEW -> ReviewStageContent(
                    page = pages[safeIndex],
                    index = safeIndex,
                    count = pages.size,
                    isArabic = isArabic,
                    isBusy = isBusy,
                    isSaving = isSaving || (!isMulti && !isExisting && cameraIsBackgroundProcessing),
                    panel = panel,
                    onPanel = { panel = if (panel == it) ToolPanel.NONE else it },
                    applyToAll = applyToAll,
                    onApplyToAll = { applyToAll = it },
                    brightness = brightness,
                    contrast = contrast,
                    onBrightness = { brightness = it },
                    onContrast = { contrast = it },
                    onApplyAdjust = { applyAdjust() },
                    onResetAdjust = { brightness = 0f; contrast = 1f },
                    onFilter = { applyFilter(it) },
                    onCrop = { panel = ToolPanel.NONE; showCropEditor = true },
                    onRotate = { rotate(it) },
                    onRetake = if (isExisting) null else onRetakePendingPage?.let { retake -> { retake(safeIndex) } },
                    onDelete = if (pages.size > 1 || !isExisting) ({ pendingDeleteIndex = safeIndex }) else null,
                    onMarkup = if (isExisting && pages[safeIndex].entityId > 0L) ({ onNavigateToAnnotate(docId, pages[safeIndex].entityId) }) else null,
                    onPrevious = { if (safeIndex > 0) currentIndex = safeIndex - 1 },
                    onRetryDetection = if (isExisting) null else ({ onRetryDetection(safeIndex) }),
                    onAcceptAsIs = if (isExisting) null else ({ onAcceptPageAsIs(safeIndex) }),
                    // Single page -> direct Save. Multi -> Save & Next, last page -> Preview.
                    primaryLabel = when {
                        !isMulti -> t("Save", "حفظ")
                        safeIndex < pages.lastIndex -> t("Save & Next", "حفظ والتالي")
                        else -> t("Finish & Preview", "إنهاء ومعاينة")
                    },
                    onPrimary = {
                        panel = ToolPanel.NONE
                        if (!isExisting) onPageReviewed(safeIndex)
                        when {
                            !isMulti -> save()
                            safeIndex < pages.lastIndex -> currentIndex = safeIndex + 1
                            else -> stage = ReviewStage.PREVIEW
                        }
                    }
                )
                else -> PreviewStageContent(
                    pages = pages,
                    isArabic = isArabic,
                    isExisting = isExisting,
                    isBusy = isBusy,
                    isSaving = isSaving,
                    backgroundProcessing = cameraIsBackgroundProcessing,
                    title = documentTitle,
                    onTitle = { documentTitle = it },
                    onOpenPage = { idx -> currentIndex = idx; stage = ReviewStage.REVIEW },
                    onMove = { from, to -> movePage(from, to) },
                    onDelete = { idx -> pendingDeleteIndex = idx },
                    canDelete = pages.size > 1 || !isExisting,
                    onAddPage = if (!isExisting) onAddPendingPage else ({ hasChanges = true; editViewModel.addBlankSessionPage() }),
                    onApplyFilterAll = { f ->
                        hasChanges = true
                        if (isExisting) editViewModel.applyEditsToSessionPages(pages.indices.toList(), true, f, null, null)
                        else onApplyPendingEdits(pages.indices.toList(), true, f, null, null)
                    },
                    onSave = { save() }
                )
            }

            if (isBusy && pages.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Emerald400)
            }
        }
    }
}

// =============================================================================================== REVIEW

@Composable
private fun ReviewStageContent(
    page: ReviewPage,
    index: Int,
    count: Int,
    isArabic: Boolean,
    isBusy: Boolean,
    isSaving: Boolean,
    panel: ToolPanel,
    onPanel: (ToolPanel) -> Unit,
    applyToAll: Boolean,
    onApplyToAll: (Boolean) -> Unit,
    brightness: Float,
    contrast: Float,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onApplyAdjust: () -> Unit,
    onResetAdjust: () -> Unit,
    onFilter: (FilterType?) -> Unit,
    onCrop: () -> Unit,
    onRotate: (Boolean) -> Unit,
    onRetake: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMarkup: (() -> Unit)?,
    onPrevious: () -> Unit,
    onRetryDetection: (() -> Unit)? = null,
    onAcceptAsIs: (() -> Unit)? = null,
    primaryLabel: String,
    onPrimary: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    Column(Modifier.fillMaxSize()) {
        // Detection failure is shown, never hidden: Retry / Manual crop / Retake / Continue.
        if (page.status.needsAttention) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (page.status == PageStatus.FAILED) t("Processing failed — original kept", "فشلت المعالجة — تم الاحتفاظ بالأصل")
                            else t("Document edges not detected — full photo kept", "لم يتم اكتشاف حواف المستند — تم الاحتفاظ بالصورة كاملة"),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        onRetryDetection?.let { TextButton(onClick = it, enabled = !isBusy) { Text(t("Retry", "إعادة المحاولة")) } }
                        TextButton(onClick = onCrop, enabled = !isBusy) { Text(t("Crop manually", "قص يدوي")) }
                        onRetake?.let { TextButton(onClick = it, enabled = !isBusy) { Text(t("Retake", "إعادة التصوير")) } }
                        onAcceptAsIs?.let { TextButton(onClick = it, enabled = !isBusy) { Text(t("Continue", "متابعة")) } }
                    }
                }
            }
        }
        // Result preview (already auto-cropped + enhanced)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = File(page.processedPath),
                contentDescription = t("Page ${index + 1}", "صفحة ${index + 1}"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
            if (count > 1) {
                Row(Modifier.align(Alignment.CenterStart).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onPrevious, enabled = index > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = t("Previous", "السابق"))
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }

        // Optional panels
        when (panel) {
            ToolPanel.FILTER -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = page.filter == null,
                            onClick = { onFilter(null) },
                            label = { Text(t("Original", "الأصلية"), fontSize = 12.sp) },
                            enabled = !isBusy
                        )
                    }
                    items(FilterType.entries.filter { it != FilterType.ORIGINAL }) { f ->
                        FilterChip(
                            selected = page.filter == f,
                            onClick = { onFilter(f) },
                            label = { Text(if (isArabic) f.displayNameAr else f.displayNameEn, fontSize = 12.sp) },
                            enabled = !isBusy
                        )
                    }
                }
                if (count > 1) ApplyToAllRow(applyToAll, onApplyToAll, isArabic)
            }
            ToolPanel.ADJUST -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AdjustmentSlider(t("Brightness", "السطوع"), brightness, onBrightness, -1f..1f, Icons.Default.Brightness6)
                AdjustmentSlider(t("Contrast", "التباين"), contrast, onContrast, 0.6f..2.0f, Icons.Default.Contrast)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (count > 1) ApplyToAllRow(applyToAll, onApplyToAll, isArabic, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    TextButton(onClick = onResetAdjust) { Text(t("Reset", "إعادة")) }
                    Button(onClick = onApplyAdjust, enabled = !isBusy) { Text(t("Apply", "تطبيق")) }
                }
            }
            ToolPanel.NONE -> Unit
        }

        // Optional tools (never required to finish the scan)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton(Icons.Default.Crop, t("Crop", "قص"), !isBusy, onClick = onCrop)
            ToolButton(Icons.Default.RotateLeft, t("Left", "يسار"), !isBusy, onClick = { onRotate(false) })
            ToolButton(Icons.Default.RotateRight, t("Right", "يمين"), !isBusy, onClick = { onRotate(true) })
            ToolButton(Icons.Default.FilterAlt, t("Filter", "فلتر"), !isBusy, selected = panel == ToolPanel.FILTER, onClick = { onPanel(ToolPanel.FILTER) })
            ToolButton(Icons.Default.Brightness6, t("Adjust", "سطوع"), !isBusy, selected = panel == ToolPanel.ADJUST, onClick = { onPanel(ToolPanel.ADJUST) })
            onRetake?.let { ToolButton(Icons.Default.CameraAlt, t("Retake", "إعادة"), !isBusy, onClick = it) }
            onDelete?.let { ToolButton(Icons.Default.Delete, t("Delete", "حذف"), !isBusy, onClick = it) }
            onMarkup?.let { ToolButton(Icons.Default.Draw, t("Markup", "تعليق"), !isBusy, onClick = it) }
        }

        Button(
            onClick = onPrimary,
            enabled = !isBusy && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Text(primaryLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }
    }
}

@Composable
private fun ApplyToAllRow(checked: Boolean, onChecked: (Boolean) -> Unit, isArabic: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked, colors = CheckboxDefaults.colors(checkedColor = Emerald400))
        Text(if (isArabic) "تطبيق على كل الصفحات" else "Apply to all pages", fontSize = 13.sp)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Emerald400.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

// =============================================================================================== PREVIEW

@Composable
private fun PreviewStageContent(
    pages: List<ReviewPage>,
    isArabic: Boolean,
    isExisting: Boolean,
    isBusy: Boolean,
    isSaving: Boolean,
    backgroundProcessing: Boolean,
    title: String,
    onTitle: (String) -> Unit,
    onOpenPage: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    canDelete: Boolean,
    onAddPage: (() -> Unit)?,
    onApplyFilterAll: (FilterType?) -> Unit,
    onSave: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var showFilterMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (!isExisting) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitle,
                label = { Text(t("Document title", "اسم المستند")) },
                leadingIcon = { Icon(Icons.Default.Title, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t("Tap a page to edit", "اضغط على صفحة لتعديلها"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { showFilterMenu = true }, enabled = !isBusy) {
                    Icon(Icons.Default.FilterAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(t("Filter all", "فلتر للكل"))
                }
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    DropdownMenuItem(text = { Text(t("Original", "الأصلية")) }, onClick = { showFilterMenu = false; onApplyFilterAll(null) })
                    FilterType.entries.filter { it != FilterType.ORIGINAL }.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(if (isArabic) f.displayNameAr else f.displayNameEn) },
                            onClick = { showFilterMenu = false; onApplyFilterAll(f) }
                        )
                    }
                }
            }
        }
        if (backgroundProcessing) {
            Text(
                t("Importing remaining pages…", "جاري استيراد بقية الصفحات…"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(pages, key = { _, p -> p.rawPath + "|" + p.entityId }) { index, page ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f)
                            .clickable(enabled = !isBusy) { onOpenPage(index) }
                    ) {
                        AsyncImage(
                            model = File(page.processedPath),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        )
                        if (page.status.needsAttention) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = t("Edges not detected", "لم تُكتشف الحواف"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(Color.White, CircleShape)
                                    .padding(2.dp)
                                    .size(16.dp)
                            )
                        }
                        Text(
                            "${index + 1}",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0 && !isBusy, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, t("Move left", "نقل لليسار"))
                        }
                        IconButton(onClick = { onDelete(index) }, enabled = canDelete && !isBusy, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Delete, t("Delete", "حذف"), Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < pages.lastIndex && !isBusy, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, t("Move right", "نقل لليمين"))
                        }
                    }
                }
            }
            if (onAddPage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.62f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Emerald400, RoundedCornerShape(10.dp))
                            .clickable(enabled = !isBusy, onClick = onAddPage),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (isExisting) Icons.Default.NoteAdd else Icons.Default.AddAPhoto, null, tint = Emerald400)
                            Text(
                                if (isExisting) t("Blank page", "صفحة فارغة") else t("Add page", "إضافة صفحة"),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !isBusy && !isSaving && !backgroundProcessing && pages.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isExisting) t("Save changes", "حفظ التعديلات") else t("Save document (${pages.size})", "حفظ المستند (${pages.size})"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    icon: ImageVector
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f", value), style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400)
        )
    }
}
