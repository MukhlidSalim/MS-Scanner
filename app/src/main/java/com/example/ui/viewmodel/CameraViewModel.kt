package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentStatus
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.model.PageStatus
import com.example.data.model.draftStatus
import com.example.data.repository.DocumentSaveRequest
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.DetectionStatus
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.DocumentAnalysisWorker
import com.example.ui.screens.editor.CropEditorResult
import com.example.ui.util.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Per-page edit parameters of a page that is not saved yet. Everything is re-rendered from the RAW
 * image with DocumentPipeline (warp(quad) -> rotate -> filter -> brightness/contrast), so edits never
 * stack on an already-processed image and the crop is never lost.
 */
data class PendingPageEdit(
    val rotation: Int = 0,
    val filter: FilterType? = DocumentPipeline.DEFAULT_FILTER,
    val brightness: Float = 0f,
    val contrast: Float = 1f
)

/** Where the next camera result goes when the camera is opened from the review screen. */
sealed class CaptureTarget {
    object Append : CaptureTarget()
    data class Replace(val index: Int) : CaptureTarget()
}

data class CameraUiState(
    val pendingPages: List<Pair<String, String>> = emptyList(),
    val pagesPendingEdit: List<Pair<String, String>> = emptyList(),
    val importedUrisPending: List<Uri> = emptyList(),
    /** True only until the FIRST page is ready, so review is usable immediately. */
    val isLoading: Boolean = false,
    /** True while remaining imported pages are processed in the background. */
    val isBackgroundProcessing: Boolean = false,
    /** True while a page edit (rotate / filter / brightness) is being rendered. */
    val isProcessingEdit: Boolean = false,
    /** True while the document is being written; blocks duplicate saves. */
    val isSaving: Boolean = false,
    /** Edit parameters per RAW path. */
    val pageEdits: Map<String, PendingPageEdit> = emptyMap(),
    /** Lifecycle of every pending page, per RAW path (see PageStatus). */
    val pageStatuses: Map<String, PageStatus> = emptyMap(),
    val detectedCategory: DocumentCategory = DocumentCategory.OTHER,
    val detectedOcrText: String = ""
) {
    fun statusOf(rawPath: String): PageStatus = pageStatuses[rawPath] ?: PageStatus.PROCESSED
    val documentStatus: DocumentStatus get() = draftStatus(pagesPendingEdit.map { statusOf(it.first) })
    val pagesNeedingReview: Int get() = pagesPendingEdit.count { statusOf(it.first).needsAttention }
    val anyPageProcessing: Boolean get() = pagesPendingEdit.any { statusOf(it.first) == PageStatus.PROCESSING }
}

/** Initial status of a freshly processed page, from the pipeline's persisted detection result. */
internal fun initialStatusFor(rawPath: String): PageStatus = when (QuadStore.loadStatus(rawPath)) {
    DetectionStatus.NOT_FOUND -> PageStatus.NEEDS_REVIEW
    DetectionStatus.MANUAL -> PageStatus.EDITED
    else -> PageStatus.PROCESSED
}

class CameraViewModel(
    context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    // Application context only: never hold an Activity in a ViewModel.
    private val context: Context = context.applicationContext

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Buffered + trySend: a missing collector can never suspend (hang) an import or a save.
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var importJob: Job? = null
    private val editMutex = Mutex()

    @Volatile
    private var captureTarget: CaptureTarget? = null

    // ------------------------------------------------------------------ capture target

    fun setCaptureTarget(target: CaptureTarget?) {
        captureTarget = target
    }

    /** Returns and clears the pending target (one-shot). */
    fun consumeCaptureTarget(): CaptureTarget? {
        val t = captureTarget
        captureTarget = null
        return t
    }

    // ------------------------------------------------------------------ session setup

    private fun setPages(pages: List<Pair<String, String>>) {
        _uiState.update { s ->
            val keys = pages.map { it.first }.toSet()
            val edits = s.pageEdits.filterKeys { it in keys }
            val statuses = pages.associate { (raw, _) -> raw to (s.pageStatuses[raw] ?: initialStatusFor(raw)) }
            s.copy(pagesPendingEdit = pages, pendingPages = pages, pageEdits = edits, pageStatuses = statuses)
        }
    }

    private fun setStatus(rawPath: String, status: PageStatus) {
        _uiState.update { s ->
            if (s.pagesPendingEdit.none { it.first == rawPath }) s
            else s.copy(pageStatuses = s.pageStatuses + (rawPath to status))
        }
    }

    /** "Save & Next": the user reviewed this page. A page whose edges were not found stays flagged. */
    fun markPageReviewed(index: Int) {
        val raw = _uiState.value.pagesPendingEdit.getOrNull(index)?.first ?: return
        val current = _uiState.value.statusOf(raw)
        if (current == PageStatus.PROCESSED || current == PageStatus.EDITED) setStatus(raw, PageStatus.READY_TO_SAVE)
    }

    /** "Continue": the user accepts the full image although no edges were detected. */
    fun acceptPageAsIs(index: Int) {
        val raw = _uiState.value.pagesPendingEdit.getOrNull(index)?.first ?: return
        if (_uiState.value.statusOf(raw).needsAttention) setStatus(raw, PageStatus.EDITED)
    }

    /** "Retry detection" for one page: full detection chain again on the untouched original. */
    fun retryDetection(index: Int) {
        val page = _uiState.value.pagesPendingEdit.getOrNull(index) ?: return
        val (rawPath, oldProcessed) = page
        if (_uiState.value.statusOf(rawPath) == PageStatus.PROCESSING) return
        viewModelScope.launch {
            editMutex.withLock {
                val previous = _uiState.value.statusOf(rawPath)
                setStatus(rawPath, PageStatus.PROCESSING)
                _uiState.update { it.copy(isProcessingEdit = true) }
                try {
                    val edit = editFor(rawPath)
                    val result = DocumentPipeline.redetect(
                        context, rawPath, rotationDegrees = edit.rotation, filter = edit.filter, prefix = "retry"
                    )
                    if (result == null) {
                        setStatus(rawPath, PageStatus.FAILED)
                        _events.trySend(UiEvent.Error("Could not read the original image"))
                        return@withLock
                    }
                    var applied = false
                    _uiState.update { s ->
                        if (s.pagesPendingEdit.none { it.first == rawPath }) return@update s
                        applied = true
                        val list = s.pagesPendingEdit.map { if (it.first == rawPath) Pair(rawPath, result.processedPath) else it }
                        val newEdit = (s.pageEdits[rawPath] ?: PendingPageEdit()).copy(brightness = 0f, contrast = 1f)
                        s.copy(pagesPendingEdit = list, pendingPages = list, pageEdits = s.pageEdits + (rawPath to newEdit))
                    }
                    if (!applied) {
                        File(result.processedPath).delete()
                        return@withLock
                    }
                    deleteIfUnreferenced(oldProcessed, rawPath)
                    if (result.detectionStatus == DetectionStatus.DETECTED) {
                        setStatus(rawPath, PageStatus.PROCESSED)
                    } else {
                        setStatus(rawPath, if (previous == PageStatus.EDITED) PageStatus.EDITED else PageStatus.NEEDS_REVIEW)
                        _events.trySend(UiEvent.Error("Edges still not found — crop manually or retake"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    setStatus(rawPath, previous)
                    _events.trySend(UiEvent.Error("Detection failed"))
                } finally {
                    _uiState.update { it.copy(isProcessingEdit = false) }
                }
            }
        }
    }

    fun setPagesPendingEdit(pages: List<Pair<String, String>>) {
        importJob?.cancel()
        _uiState.update {
            it.copy(
                pagesPendingEdit = pages,
                pendingPages = pages,
                importedUrisPending = emptyList(),
                pageEdits = emptyMap(),
                pageStatuses = pages.associate { (raw, _) -> raw to initialStatusFor(raw) },
                isLoading = false,
                isBackgroundProcessing = false,
                isSaving = false,
                detectedCategory = DocumentCategory.OTHER,
                detectedOcrText = ""
            )
        }
    }

    fun appendPendingPages(pages: List<Pair<String, String>>) {
        if (pages.isEmpty()) return
        setPages(_uiState.value.pagesPendingEdit + pages)
    }

    fun replacePendingPage(index: Int, page: Pair<String, String>) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index !in pages.indices) {
            appendPendingPages(listOf(page))
            return
        }
        val old = pages[index]
        pages[index] = page
        setPages(pages)
        deletePageFiles(old)
    }

    fun deletePendingPage(index: Int) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index !in pages.indices) return
        val removed = pages.removeAt(index)
        setPages(pages)
        deletePageFiles(removed)
    }

    fun movePendingPage(from: Int, to: Int) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (from !in pages.indices || to !in pages.indices || from == to) return
        pages.add(to, pages.removeAt(from))
        setPages(pages)
    }

    /** Gallery import through the same pipeline as the camera. First page is usable immediately. */
    fun setImportedUrisPendingEdit(uris: List<Uri>, autoCrop: Boolean = true) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        _uiState.update {
            it.copy(
                importedUrisPending = uris,
                pagesPendingEdit = emptyList(),
                pendingPages = emptyList(),
                pageEdits = emptyMap(),
                pageStatuses = emptyMap(),
                isLoading = true,
                isBackgroundProcessing = true,
                isSaving = false
            )
        }
        importJob = viewModelScope.launch {
            var failed = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    val page = try {
                        DocumentPipeline.processUri(context, uri, autoCrop = autoCrop, prefix = "import_p${index + 1}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        null
                    }
                    if (page != null) {
                        val pair = Pair(page.rawPath, page.processedPath)
                        _uiState.update {
                            val list = it.pagesPendingEdit + pair
                            it.copy(
                                pagesPendingEdit = list, pendingPages = list, isLoading = false,
                                pageStatuses = it.pageStatuses + (pair.first to initialStatusFor(pair.first))
                            )
                        }
                    } else {
                        failed++
                    }
                }
                if (failed > 0) _events.trySend(UiEvent.Error("Could not import $failed image(s)"))
            } finally {
                _uiState.update { it.copy(isLoading = false, isBackgroundProcessing = false) }
            }
        }
    }

    /** PDF import through the same pipeline (pages rasterized; no border detection on flat pages). */
    fun setImportedPdfPendingEdit(uri: Uri) {
        importJob?.cancel()
        _uiState.update {
            it.copy(
                importedUrisPending = emptyList(), pagesPendingEdit = emptyList(), pendingPages = emptyList(),
                pageEdits = emptyMap(), pageStatuses = emptyMap(),
                isLoading = true, isBackgroundProcessing = true, isSaving = false
            )
        }
        importJob = viewModelScope.launch {
            try {
                val pages = DocumentPipeline.importPdf(context, uri, filter = null) { _, page ->
                    val pair = Pair(page.rawPath, page.processedPath)
                    _uiState.update {
                        val list = it.pagesPendingEdit + pair
                        it.copy(
                            pagesPendingEdit = list, pendingPages = list, isLoading = false,
                            pageEdits = it.pageEdits + (page.rawPath to PendingPageEdit(filter = null)),
                            pageStatuses = it.pageStatuses + (page.rawPath to PageStatus.PROCESSED)
                        )
                    }
                }
                if (pages.isEmpty()) _events.trySend(UiEvent.Error("Could not read PDF"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.trySend(UiEvent.Error("Could not read PDF"))
            } finally {
                _uiState.update { it.copy(isLoading = false, isBackgroundProcessing = false) }
            }
        }
    }

    // ------------------------------------------------------------------ page edits

    private fun editFor(rawPath: String): PendingPageEdit =
        _uiState.value.pageEdits[rawPath] ?: PendingPageEdit()

    private fun quadFor(rawPath: String): DocumentQuad = QuadStore.load(rawPath) ?: DocumentQuad.fullQuad()

    /**
     * Re-renders the given pages from RAW with an updated edit. Pages are matched by RAW path, so a
     * delete / reorder performed while rendering can never write a result onto the wrong page.
     */
    private fun renderPages(indices: List<Int>, transform: (PendingPageEdit) -> PendingPageEdit) {
        val snapshot = _uiState.value.pagesPendingEdit
        val targets = indices.mapNotNull { snapshot.getOrNull(it) }.distinctBy { it.first }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            editMutex.withLock {
                _uiState.update { it.copy(isProcessingEdit = true) }
                try {
                    for ((rawPath, oldProcessed) in targets) {
                        if (rawPath.isBlank()) continue
                        val before = _uiState.value.statusOf(rawPath)
                        setStatus(rawPath, PageStatus.PROCESSING)
                        val newEdit = transform(editFor(rawPath))
                        val newPath = DocumentPipeline.render(
                            context, rawPath, quadFor(rawPath), newEdit.rotation, newEdit.filter,
                            brightness = newEdit.brightness, contrast = newEdit.contrast, prefix = "edit_pending"
                        )
                        if (newPath == null) {
                            setStatus(rawPath, before)
                            _events.trySend(UiEvent.Error("Could not read the original image"))
                            continue
                        }
                        var applied = false
                        _uiState.update { s ->
                            if (s.pagesPendingEdit.none { it.first == rawPath }) return@update s
                            applied = true
                            val list = s.pagesPendingEdit.map { if (it.first == rawPath) Pair(rawPath, newPath) else it }
                            s.copy(pagesPendingEdit = list, pendingPages = list, pageEdits = s.pageEdits + (rawPath to newEdit))
                        }
                        if (applied) deleteIfUnreferenced(oldProcessed, rawPath) else File(newPath).delete()
                        // Rotate / filter / brightness do not fix a missing crop: the flag stays until crop / continue.
                        setStatus(rawPath, if (before.needsAttention) before else PageStatus.EDITED)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    _events.trySend(UiEvent.Error("Failed to update page"))
                } finally {
                    // No page may stay stuck in PROCESSING (it would block Save forever).
                    _uiState.update { s ->
                        s.copy(
                            isProcessingEdit = false,
                            pageStatuses = s.pageStatuses.mapValues { (_, v) -> if (v == PageStatus.PROCESSING) PageStatus.EDITED else v }
                        )
                    }
                }
            }
        }
    }

    fun rotatePendingPage(index: Int, clockwise: Boolean) {
        val delta = if (clockwise) 90 else 270
        renderPages(listOf(index)) { it.copy(rotation = (it.rotation + delta) % 360) }
    }

    /**
     * Applies a filter and/or brightness/contrast to one or several pages ("Apply to all").
     * [filter] is applied only when [changeFilter] is true (null filter = Original colors).
     */
    fun applyEditsToPendingPages(
        indices: List<Int>,
        changeFilter: Boolean,
        filter: FilterType?,
        brightness: Float?,
        contrast: Float?
    ) {
        renderPages(indices) { e ->
            e.copy(
                filter = if (changeFilter) filter else e.filter,
                brightness = brightness ?: e.brightness,
                contrast = contrast ?: e.contrast
            )
        }
    }

    /** Result of the crop editor (quad already persisted in QuadStore by DocumentPipeline.render). */
    fun updatePendingPageCrop(index: Int, result: CropEditorResult) {
        val pages = _uiState.value.pagesPendingEdit
        val page = pages.getOrNull(index) ?: return
        val rawPath = page.first
        QuadStore.save(rawPath, result.quad)
        QuadStore.saveStatus(rawPath, DetectionStatus.MANUAL)
        _uiState.update { s ->
            val list = s.pagesPendingEdit.map { if (it.first == rawPath) Pair(rawPath, result.processedPath) else it }
            val edit = (s.pageEdits[rawPath] ?: PendingPageEdit()).copy(
                rotation = result.rotationDegrees, filter = result.filter, brightness = 0f, contrast = 1f
            )
            s.copy(
                pagesPendingEdit = list, pendingPages = list, pageEdits = s.pageEdits + (rawPath to edit),
                pageStatuses = s.pageStatuses + (rawPath to PageStatus.EDITED)
            )
        }
        if (page.second != result.processedPath) deleteIfUnreferenced(page.second, rawPath)
    }

    /** Legacy entry point (crop editor without result object). */
    fun updatePendingPageProcessedImage(index: Int, newPath: String) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index !in pages.indices) return
        val old = pages[index]
        pages[index] = Pair(old.first, newPath)
        setPages(pages)
        if (old.second != newPath) deleteIfUnreferenced(old.second, old.first)
    }

    fun autoCropAllPendingPages() {
        val currentPages = _uiState.value.pagesPendingEdit
        if (currentPages.isEmpty()) return
        viewModelScope.launch {
            editMutex.withLock {
                _uiState.update { it.copy(isProcessingEdit = true) }
                try {
                    for ((rawPath, oldProcessed) in currentPages) {
                        val edit = editFor(rawPath)
                        val page = DocumentPipeline.redetect(
                            context, rawPath, rotationDegrees = edit.rotation, filter = edit.filter, prefix = "recrop"
                        ) ?: continue
                        _uiState.update { s ->
                            val list = s.pagesPendingEdit.map { if (it.first == rawPath) Pair(rawPath, page.processedPath) else it }
                            val st = when (page.detectionStatus) {
                                DetectionStatus.DETECTED -> PageStatus.PROCESSED
                                DetectionStatus.MANUAL -> PageStatus.EDITED
                                else -> PageStatus.NEEDS_REVIEW
                            }
                            s.copy(pagesPendingEdit = list, pendingPages = list, pageStatuses = s.pageStatuses + (rawPath to st))
                        }
                        deleteIfUnreferenced(oldProcessed, rawPath)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    _events.trySend(UiEvent.Error("Failed to auto-crop pending pages"))
                } finally {
                    _uiState.update { it.copy(isProcessingEdit = false) }
                }
            }
        }
    }

    /** Kept for API compatibility. Analysis now runs in the background AFTER save, never before. */
    fun analyzePendingFirstPage() = Unit

    // ------------------------------------------------------------------ save

    private fun filterNameFor(edit: PendingPageEdit): String = (edit.filter ?: FilterType.ORIGINAL).name

    /** Pending pages -> PageEntity rows with their final crop / rotation / filter (id = 0: new rows). */
    private fun pendingAsEntities(): List<PageEntity> {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        return state.pagesPendingEdit.mapIndexed { index, (rawPath, procPath) ->
            val e = state.pageEdits[rawPath] ?: PendingPageEdit()
            PageEntity(
                documentId = 0L,
                pageIndex = index,
                rawImagePath = rawPath,
                processedImagePath = procPath,
                rotationDegrees = e.rotation,
                filterType = filterNameFor(e),
                cropQuadJson = (QuadStore.load(rawPath) ?: DocumentQuad.fullQuad()).toJson(),
                createdAt = now
            )
        }
    }

    /** Guards shared by every save entry point. Returns an error message, or null when saving is allowed. */
    private fun saveBlockedReason(): String? {
        val state = _uiState.value
        return when {
            state.isSaving -> "Already saving"
            state.isBackgroundProcessing -> "Please wait until all pages are imported"
            state.isProcessingEdit || state.anyPageProcessing -> "Please wait until page processing finishes"
            state.pagesPendingEdit.isEmpty() -> "No pages to save"
            else -> null
        }
    }

    /**
     * New document from the scan session (camera, gallery, PDF, multi-page). One Room transaction via
     * DocumentRepository.saveDocument: document + all pages + metadata + thumbnail, or nothing.
     */
    fun importPagesAsDocument(title: String = "", selectedFolder: String = "Default", onComplete: (Long) -> Unit) {
        saveBlockedReason()?.let { reason ->
            if (reason != "Already saving") _events.trySend(UiEvent.Error(reason))
            return
        }
        val pages = pendingAsEntities()
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val folder = selectedFolder.takeUnless { it.isBlank() || it.equals("ALL", ignoreCase = true) } ?: "Default"
                val docId = repository.saveDocument(
                    DocumentSaveRequest(existingDocId = null, title = title, folderName = folder, pages = pages)
                )
                markSavedAndClear()
                onComplete(docId)
                analyzeDocumentInBackground(docId, generatedTitle = title.isBlank() || title.startsWith("Doc_"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // Nothing was written (transaction rolled back): pages stay in the session, user can retry.
                _events.trySend(UiEvent.Error("Failed to save document: ${e.localizedMessage ?: "storage error"}"))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** Adds the session pages to an existing document, through the same transaction. */
    fun commitPendingPagesToDocument(docId: Long, onComplete: () -> Unit) {
        saveBlockedReason()?.let { reason ->
            if (reason != "Already saving") _events.trySend(UiEvent.Error(reason))
            return
        }
        val newPages = pendingAsEntities()
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val doc = repository.getDocumentById(docId)
                if (doc == null) {
                    _events.trySend(UiEvent.Error("Document not found"))
                    return@launch
                }
                val existing = repository.getPagesList(docId)
                repository.saveDocument(
                    DocumentSaveRequest(
                        existingDocId = docId,
                        title = doc.title,
                        folderName = doc.folderName,
                        pages = existing + newPages
                    )
                )
                markSavedAndClear()
                onComplete()
                analyzeDocumentInBackground(docId, generatedTitle = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.trySend(UiEvent.Error("Failed to save pages: ${e.localizedMessage ?: "storage error"}"))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun markSavedAndClear() {
        _uiState.update { s -> s.copy(pageStatuses = s.pageStatuses.mapValues { PageStatus.SAVED }) }
        clearPendingPages(deleteFiles = false)
    }

    /**
     * OCR + classification + suggested title run strictly AFTER save through WorkManager
     * (DocumentAnalysisWorker): survives navigation / process death and never blocks the scan flow.
     */
    private fun analyzeDocumentInBackground(docId: Long, generatedTitle: Boolean) {
        DocumentAnalysisWorker.enqueue(context, docId, generatedTitle)
    }

    // ------------------------------------------------------------------ cleanup

    fun clearPendingPages(deleteFiles: Boolean = false) {
        importJob?.cancel()
        if (deleteFiles) {
            val state = _uiState.value
            (state.pendingPages + state.pagesPendingEdit).distinct().forEach { deletePageFiles(it) }
        }
        _uiState.update {
            it.copy(
                pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList(),
                pageEdits = emptyMap(), pageStatuses = emptyMap(), isLoading = false, isBackgroundProcessing = false,
                detectedCategory = DocumentCategory.OTHER, detectedOcrText = ""
            )
        }
    }

    private fun deletePageFiles(page: Pair<String, String>) {
        runCatching {
            if (page.first.isNotBlank()) {
                QuadStore.delete(page.first)
                File(page.first).delete()
            }
            if (page.second.isNotBlank() && page.second != page.first) File(page.second).delete()
        }
    }

    /** Deletes an obsolete processed file unless it is the raw image or still used by a page. */
    private fun deleteIfUnreferenced(path: String, rawPath: String) {
        if (path.isBlank() || path == rawPath) return
        if (_uiState.value.pagesPendingEdit.any { it.first == path || it.second == path }) return
        runCatching { File(path).delete() }
    }
}
