package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.engine.annotation.AnnotationEngine
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.engine.cv.QualityReport
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.DocumentAiEngine
import com.example.engine.ocr.DocumentAnalysisResult
import com.example.engine.ocr.DocumentAnalysisWorker
import com.example.engine.ocr.OcrLanguage
import com.example.engine.ocr.OcrLayoutStore
import com.example.engine.ocr.TessDataManager
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.screens.editor.CropEditorResult
import com.example.ui.util.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class EditSessionUiState(
    val activeDocument: DocumentEntity? = null,
    val activePages: List<PageEntity> = emptyList(),
    val selectedPageIndex: Int = 0,
    val editingSessionPages: List<PageEntity> = emptyList(),
    val isEditingSession: Boolean = false,
    val currentQualityReport: QualityReport? = null,
    val isOcrLoading: Boolean = false,
    val ocrResult: DocumentAnalysisResult? = null,
    val exportedPdfFile: File? = null,
    val isExportingPdf: Boolean = false,
    val savedSignatures: List<SignatureEntity> = emptyList(),
    val defaultPdfPageSize: PageSizePreset = PageSizePreset.A4,
    val defaultPdfCompression: CompressionPreset = CompressionPreset.HIGH,
    val selectedCompression: CompressionPreset = CompressionPreset.HIGH,
    val ocrLanguage: com.example.engine.ocr.OcrLanguage = com.example.engine.ocr.OcrLanguage.AUTO,
    val isLoading: Boolean = false,
    /** True while an editing session is being committed; blocks duplicate saves. */
    val isSaving: Boolean = false,
)

class EditSessionViewModel(
    context: Context,
    private val repository: DocumentRepository
) : ViewModel() {
    private val context: Context = context.applicationContext
    private val prefs = AppPreferences(this.context)
    private var originalSessionPages = listOf<PageEntity>()
    private val _uiState = MutableStateFlow(EditSessionUiState(
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression,
        // Previously always AUTO here: the language chosen in Settings was ignored by the OCR screen.
        ocrLanguage = runCatching { OcrLanguage.valueOf(prefs.ocrLanguage) }.getOrDefault(OcrLanguage.AUTO)
    ))
    val uiState: StateFlow<EditSessionUiState> = _uiState.asStateFlow()
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val qualityCache = mutableMapOf<Long, QualityReport>()
    private val sessionMutex = Mutex()
    private var loadJob: Job? = null

    /** Per-session brightness/contrast (not stored in DB) keyed by raw path. */
    private val sessionAdjust = mutableMapOf<String, Pair<Float, Float>>()

    init {
        loadSignatures()
    }

    private fun loadSignatures() {
        viewModelScope.launch {
            repository.getAllSignatures().collectLatest { sigs ->
                _uiState.update { it.copy(savedSignatures = sigs) }
            }
        }
    }

    /** Stored quad for a page: DB json -> sidecar -> full image. Never an arbitrary inset. */
    private fun quadFor(page: PageEntity): DocumentQuad =
        DocumentQuad.fromJsonOrNull(page.cropQuadJson) ?: QuadStore.load(page.rawImagePath) ?: DocumentQuad.fullQuad()

    private fun filterOf(page: PageEntity): FilterType? =
        runCatching { FilterType.valueOf(page.filterType) }.getOrNull()?.takeIf { it != FilterType.ORIGINAL }

    private fun rawOf(page: PageEntity): String = page.rawImagePath.ifBlank { page.processedImagePath }

    private suspend fun refreshThumbnail(docId: Long, firstPagePath: String?) {
        val doc = repository.getDocumentById(docId) ?: return
        doc.thumbnailPath?.let { runCatching { File(it).delete() } }
        val thumb = firstPagePath?.let { ImageProcessor.createThumbnail(context, it) }
        repository.updateDocument(doc.copy(thumbnailPath = thumb))
    }

    fun updateActivePageProcessedImage(newPath: String) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]
        viewModelScope.launch {
            val updatedPage = page.copy(processedImagePath = newPath)
            repository.updatePage(updatedPage)
            if (page.processedImagePath != newPath && page.processedImagePath != page.rawImagePath) {
                runCatching { File(page.processedImagePath).delete() }
            }
            _uiState.update { s -> s.copy(activePages = s.activePages.map { if (it.id == page.id) updatedPage else it }) }
            qualityCache.remove(page.id)
            if (idx == 0) refreshThumbnail(page.documentId, newPath)
            DocumentAnalysisWorker.enqueue(context, page.documentId, generatedTitle = false)
        }
    }

    /** Crop editor result for a saved page: stores quad / rotation / filter with the page. */
    fun updateActivePageCrop(pageId: Long, result: CropEditorResult) {
        viewModelScope.launch {
            val page = repository.getPageById(pageId) ?: return@launch
            val updated = page.copy(
                processedImagePath = result.processedPath,
                cropQuadJson = result.quad.toJson(),
                rotationDegrees = result.rotationDegrees,
                filterType = (result.filter ?: FilterType.ORIGINAL).name
            )
            repository.updatePage(updated)
            if (page.processedImagePath != result.processedPath && page.processedImagePath != page.rawImagePath) {
                runCatching { File(page.processedImagePath).delete() }
            }
            qualityCache.remove(pageId)
            if (page.pageIndex == 0) refreshThumbnail(page.documentId, result.processedPath)
            // New crop / rotation = new text positions: refresh OCR + searchable layer in the background.
            DocumentAnalysisWorker.enqueue(context, page.documentId, generatedTitle = false)
        }
    }

    fun replacePage(pageId: Long, newRawPath: String, newProcessedPath: String) {
        viewModelScope.launch {
            val page = repository.getPageById(pageId) ?: return@launch
            // The stored filter must describe the new image exactly, otherwise a later rotate / re-render
            // applies a filter a second time:
            //  - Google document scanner pages and PDF pages (status SKIPPED) are already processed -> ORIGINAL
            //  - camera / gallery pages were rendered by the pipeline with its default filter
            val alreadyProcessed = QuadStore.loadStatus(newRawPath) == com.example.engine.cv.DetectionStatus.SKIPPED
            val updatedPage = page.copy(
                rawImagePath = newRawPath,
                processedImagePath = newProcessedPath,
                cropQuadJson = (QuadStore.load(newRawPath) ?: DocumentQuad.fullQuad()).toJson(),
                rotationDegrees = 0,
                filterType = if (alreadyProcessed) FilterType.ORIGINAL.name else DocumentPipeline.DEFAULT_FILTER.name,
                ocrText = ""
            )
            repository.updatePage(updatedPage)
            runCatching {
                if (page.rawImagePath.isNotBlank() && page.rawImagePath != newRawPath) {
                    QuadStore.delete(page.rawImagePath); File(page.rawImagePath).delete()
                }
                if (page.processedImagePath.isNotBlank() && page.processedImagePath != newProcessedPath) {
                    File(page.processedImagePath).delete()
                    OcrLayoutStore.delete(page.processedImagePath)
                }
            }
            if (page.pageIndex == 0) refreshThumbnail(page.documentId, newProcessedPath)
            qualityCache.remove(pageId)
            loadDocument(page.documentId)
            DocumentAnalysisWorker.enqueue(context, page.documentId, generatedTitle = false)
        }
    }

    fun loadDocument(docId: Long) {
        // One collector per document: previous calls created a new never-ending collector every time.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val doc = repository.getDocumentById(docId)
            _uiState.update {
                val keepIndex = if (it.activeDocument?.id == docId) it.selectedPageIndex else 0
                it.copy(activeDocument = doc, selectedPageIndex = keepIndex)
            }
            repository.getPagesForDocument(docId).collectLatest { pages ->
                // Keep the title / OCR metadata fresh (background analysis may rename the document).
                repository.getDocumentById(docId)?.let { fresh -> _uiState.update { it.copy(activeDocument = fresh) } }
                _uiState.update { s ->
                    s.copy(activePages = pages, selectedPageIndex = s.selectedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                }
                pages.getOrNull(_uiState.value.selectedPageIndex)?.let { evaluateQuality(it) }
            }
        }
    }

    fun selectPageIndex(index: Int) {
        val pages = _uiState.value.activePages
        if (index in pages.indices) {
            _uiState.update { it.copy(selectedPageIndex = index) }
            viewModelScope.launch { evaluateQuality(pages[index]) }
        }
    }

    private suspend fun evaluateQuality(page: PageEntity) {
        val cached = qualityCache[page.id]
        if (cached != null) {
            _uiState.update { it.copy(currentQualityReport = cached) }
        } else {
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 500)
            if (bmp != null) {
                try {
                    val report = ImageProcessor.analyzeQuality(bmp)
                    qualityCache[page.id] = report
                    _uiState.update { it.copy(currentQualityReport = report) }
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    // =========================================================================== editing session
    // The session is an in-memory copy of the document pages. Nothing is written to the database
    // until commitEditingSessionChanges(); cancel discards only the files created by the session.

    fun startEditingSession(pages: List<PageEntity>) {
        originalSessionPages = pages
        sessionAdjust.clear()
        _uiState.update { it.copy(editingSessionPages = pages, isEditingSession = true, isSaving = false) }
    }

    fun endEditingSession() {
        val originals = originalSessionPages
        val originalFiles = originals.flatMap { listOf(it.rawImagePath, it.processedImagePath) }.toSet()
        _uiState.value.editingSessionPages.forEach { p ->
            if (p.processedImagePath !in originalFiles) runCatching { File(p.processedImagePath).delete() }
            if (p.id == 0L && p.rawImagePath !in originalFiles) runCatching { File(p.rawImagePath).delete() }
        }
        originalSessionPages = emptyList()
        sessionAdjust.clear()
        _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
    }

    /**
     * Commits the editing session through the single save transaction (DocumentRepository.saveDocument):
     * deletions, updates, new pages (blank / merged) and the final order are written atomically.
     * Obsolete files are deleted only AFTER a successful commit, so a failed save never loses pages.
     */
    fun commitEditingSessionChanges(onComplete: () -> Unit) {
        if (_uiState.value.isSaving || _uiState.value.isLoading) return
        val pages = _uiState.value.editingSessionPages
        if (pages.isEmpty()) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                sessionMutex.withLock {
                    val doc = _uiState.value.activeDocument
                    val docId = doc?.id ?: pages.first().documentId
                    val finalPages = _uiState.value.editingSessionPages
                    val keptIds = finalPages.map { it.id }.filter { it != 0L }.toSet()
                    val removed = originalSessionPages.filter { it.id !in keptIds }
                    repository.saveDocument(
                        com.example.data.repository.DocumentSaveRequest(
                            existingDocId = docId,
                            title = doc?.title ?: "",
                            folderName = doc?.folderName ?: "Default",
                            pages = finalPages,
                            removedPageIds = removed.map { it.id }
                        )
                    )
                    // Commit succeeded: now it is safe to delete replaced / removed files.
                    val stillUsed = finalPages.flatMap { listOf(it.rawImagePath, it.processedImagePath) }.toSet()
                    for (orig in originalSessionPages) {
                        listOf(orig.rawImagePath, orig.processedImagePath).distinct().forEach { path ->
                            if (path.isNotBlank() && path !in stillUsed) {
                                runCatching { File(path).delete() }
                                if (path == orig.rawImagePath) QuadStore.delete(path)
                            }
                        }
                    }
                    qualityCache.clear()
                    originalSessionPages = emptyList()
                    sessionAdjust.clear()
                    _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
                    onComplete()
                    // OCR / classification for new or replaced pages, in the background (WorkManager).
                    DocumentAnalysisWorker.enqueue(context, docId, generatedTitle = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to save changes: ${e.localizedMessage ?: "storage error"}"))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun updateEditingSessionPageProcessedImage(index: Int, newPath: String) {
        _uiState.update { s ->
            val pages = s.editingSessionPages.toMutableList()
            if (index !in pages.indices) return@update s
            pages[index] = pages[index].copy(processedImagePath = newPath)
            s.copy(editingSessionPages = pages)
        }
    }

    fun updateEditingSessionPageCrop(index: Int, result: CropEditorResult) {
        _uiState.update { s ->
            val pages = s.editingSessionPages.toMutableList()
            if (index !in pages.indices) return@update s
            pages[index] = pages[index].copy(
                processedImagePath = result.processedPath,
                cropQuadJson = result.quad.toJson(),
                rotationDegrees = result.rotationDegrees,
                filterType = (result.filter ?: FilterType.ORIGINAL).name
            )
            s.copy(editingSessionPages = pages)
        }
        sessionAdjust.remove(result.rawPath)
    }

    /**
     * Re-renders session pages from RAW (warp -> rotate -> filter -> adjust). Session only: nothing is
     * written to the DB here. Pages are matched by raw path so a concurrent delete/reorder is safe.
     */
    private fun renderSessionPages(
        indices: List<Int>,
        transform: (rotation: Int, filter: FilterType?, adjust: Pair<Float, Float>) -> Triple<Int, FilterType?, Pair<Float, Float>>
    ) {
        val targets = indices.mapNotNull { _uiState.value.editingSessionPages.getOrNull(it) }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            sessionMutex.withLock {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    for (page in targets) {
                        val raw = rawOf(page)
                        val (rot, filter, adj) = transform(page.rotationDegrees, filterOf(page), sessionAdjust[raw] ?: (0f to 1f))
                        val newPath = DocumentPipeline.render(
                            context, raw, quadFor(page), rot, filter,
                            brightness = adj.first, contrast = adj.second, prefix = "edit_session"
                        ) ?: continue
                        sessionAdjust[raw] = adj
                        _uiState.update { s ->
                            s.copy(editingSessionPages = s.editingSessionPages.map {
                                if (rawOf(it) == raw) it.copy(
                                    processedImagePath = newPath,
                                    rotationDegrees = rot,
                                    filterType = (filter ?: FilterType.ORIGINAL).name
                                ) else it
                            })
                        }
                        qualityCache.remove(page.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    _events.send(UiEvent.Error("Failed to update page"))
                } finally {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun rotateEditingSessionPage(index: Int, clockwise: Boolean) {
        val delta = if (clockwise) 90 else 270
        renderSessionPages(listOf(index)) { rot, f, adj -> Triple((rot + delta) % 360, f, adj) }
    }

    fun applyEditsToSessionPages(indices: List<Int>, changeFilter: Boolean, filter: FilterType?, brightness: Float?, contrast: Float?) {
        renderSessionPages(indices) { rot, f, adj ->
            Triple(rot, if (changeFilter) filter else f, Pair(brightness ?: adj.first, contrast ?: adj.second))
        }
    }

    fun deleteSessionPage(index: Int) {
        _uiState.update { s ->
            val pages = s.editingSessionPages.toMutableList()
            if (index !in pages.indices) return@update s
            pages.removeAt(index)
            s.copy(editingSessionPages = pages)
        }
    }

    fun moveSessionPage(from: Int, to: Int) {
        _uiState.update { s ->
            val pages = s.editingSessionPages.toMutableList()
            if (from !in pages.indices || to !in pages.indices || from == to) return@update s
            pages.add(to, pages.removeAt(from))
            s.copy(editingSessionPages = pages)
        }
    }

    // =========================================================================== saved-page operations

    fun batchAutoCropAllSessionPages() {
        val inSession = _uiState.value.isEditingSession
        val pages = if (inSession) _uiState.value.editingSessionPages else _uiState.value.activePages
        if (pages.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                for (page in pages) {
                    val raw = rawOf(page)
                    val result = DocumentPipeline.redetect(
                        context, raw, rotationDegrees = page.rotationDegrees, filter = filterOf(page), prefix = "batch_recrop"
                    ) ?: continue
                    val updated = page.copy(processedImagePath = result.processedPath, cropQuadJson = result.quad.toJson())
                    if (inSession) {
                        _uiState.update { s -> s.copy(editingSessionPages = s.editingSessionPages.map { if (rawOf(it) == raw) updated else it }) }
                    } else {
                        repository.updatePage(updated)
                    }
                    qualityCache.remove(page.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to auto-crop pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun rerenderSavedPage(page: PageEntity, rotation: Int, filter: FilterType?, prefix: String): PageEntity? {
        val newPath = DocumentPipeline.render(context, rawOf(page), quadFor(page), rotation, filter, prefix = prefix) ?: return null
        val updated = page.copy(
            processedImagePath = newPath,
            rotationDegrees = rotation,
            filterType = (filter ?: FilterType.ORIGINAL).name,
            cropQuadJson = quadFor(page).toJson()
        )
        repository.updatePage(updated)
        if (rotation == page.rotationDegrees) {
            // Same geometry (filter only): the text layout is still exact, no new OCR needed.
            OcrLayoutStore.copy(page.processedImagePath, newPath)
        } else {
            DocumentAnalysisWorker.enqueue(context, page.documentId, generatedTitle = false)
        }
        if (page.processedImagePath != page.rawImagePath && page.processedImagePath != newPath) {
            runCatching { File(page.processedImagePath).delete() }
            OcrLayoutStore.delete(page.processedImagePath)
        }
        qualityCache.remove(page.id)
        if (page.pageIndex == 0) refreshThumbnail(page.documentId, newPath)
        return updated
    }

    fun applyFilterToActivePage(filter: FilterType) {
        val page = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                rerenderSavedPage(page, page.rotationDegrees, filter.takeIf { it != FilterType.ORIGINAL }, "filter_active")
                    ?: throw IllegalStateException("Unable to load original page")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to apply filter"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun rotateActivePage() {
        val page = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        rotatePage(page.id)
    }

    fun smartEnhanceActivePage() {
        val page = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
                if (bmp != null) {
                    val enhanced = ImageProcessor.applySmartEnhance(bmp)
                    val newPath = try {
                        ImageProcessor.saveBitmapToFile(context, enhanced, "smart_enh_")
                    } finally {
                        if (enhanced !== bmp) enhanced.recycle()
                        bmp.recycle()
                    }
                    repository.updatePage(page.copy(processedImagePath = newPath))
                    OcrLayoutStore.copy(page.processedImagePath, newPath)
                    if (page.processedImagePath != page.rawImagePath) {
                        runCatching { File(page.processedImagePath).delete() }
                        OcrLayoutStore.delete(page.processedImagePath)
                    }
                    qualityCache.remove(page.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Smart enhance failed"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun rotatePage(pageId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val page = repository.getPageById(pageId) ?: return@launch
                val newRotation = ((page.rotationDegrees + 90) % 360 + 360) % 360
                rerenderSavedPage(page, newRotation, filterOf(page), "rotate")
                    ?: throw IllegalStateException("Unable to load original page")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to rotate page"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun deleteActivePage() {
        val pages = _uiState.value.activePages
        val page = pages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        deletePageById(page.id)
    }

    /** Favorite / title / category / tags of the open document; the screen updates immediately. */
    fun updateDocumentMetadata(
        docId: Long,
        title: String? = null,
        category: String? = null,
        tagsCsv: String? = null,
        favorite: Boolean? = null
    ) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            val updated = doc.copy(
                title = title?.takeIf { it.isNotBlank() } ?: doc.title,
                category = category ?: doc.category,
                tagsCsv = tagsCsv ?: doc.tagsCsv,
                isFavorite = favorite ?: doc.isFavorite
            )
            if (updated == doc) return@launch
            repository.updateDocument(updated)
            _uiState.update { s -> if (s.activeDocument?.id == docId) s.copy(activeDocument = updated) else s }
        }
    }

    fun renameDocument(docId: Long, newTitle: String) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            repository.updateDocument(doc.copy(title = newTitle))
            _uiState.update { it.copy(activeDocument = doc.copy(title = newTitle)) }
        }
    }

    fun shareDocumentsAsPdf(context: Context, docIds: List<Long>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            try {
                val allPages = mutableListOf<PageEntity>()
                for (id in docIds) allPages.addAll(repository.getPagesForDocumentSync(id))
                if (allPages.isEmpty()) return@launch
                val config = PdfExportConfig(
                    title = "Exported_PDF",
                    pageSize = _uiState.value.defaultPdfPageSize,
                    compression = _uiState.value.defaultPdfCompression
                )
                val pairs = allPages.map { page ->
                    val img = if (page.processedImagePath.isNotBlank() && File(page.processedImagePath).exists()) page.processedImagePath else page.rawImagePath
                    Pair(img, page.ocrText)
                }
                val pdfFile = PdfEngine.generatePdf(this@EditSessionViewModel.context, pairs, config)
                _uiState.update { it.copy(exportedPdfFile = pdfFile) }
                PdfEngine.sharePdf(context, pdfFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to share PDF"))
            } finally {
                _uiState.update { it.copy(isExportingPdf = false) }
            }
        }
    }

    fun addBlankSessionPage() {
        viewModelScope.launch {
            val path = withContext(Dispatchers.Default) {
                val blankBmp = android.graphics.Bitmap.createBitmap(1240, 1754, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    blankBmp.eraseColor(android.graphics.Color.WHITE)
                    ImageProcessor.saveBitmapToFile(context, blankBmp, "blank_session_")
                } finally {
                    blankBmp.recycle()
                }
            }
            _uiState.update { s ->
                val newPage = PageEntity(
                    documentId = s.activeDocument?.id ?: 0L,
                    rawImagePath = path,
                    processedImagePath = path,
                    pageIndex = s.editingSessionPages.size,
                    filterType = FilterType.ORIGINAL.name
                )
                s.copy(editingSessionPages = s.editingSessionPages + newPage)
            }
        }
    }

    fun mergeSessionPages(indices: Set<Int>, onComplete: (String) -> Unit) {
        val pages = _uiState.value.editingSessionPages
        val sorted = indices.filter { it in pages.indices }.sorted()
        val selectedPages = sorted.map { pages[it] }
        if (selectedPages.size < 2) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bitmaps = selectedPages.mapNotNull { ImageProcessor.loadBitmapFromFile(it.processedImagePath, 1600) }
                if (bitmaps.size >= 2) {
                    val merged = ImageProcessor.mergeBitmapsVertical(bitmaps)
                    val path = try {
                        ImageProcessor.saveBitmapToFile(context, merged, "merged_session_")
                    } finally {
                        bitmaps.forEach { it.recycle() }
                        merged.recycle()
                    }
                    val firstIdx = sorted.first()
                    // Merged page becomes a NEW page (its raw is the merged image); originals are removed on commit.
                    val newPage = selectedPages.first().copy(
                        id = 0L, processedImagePath = path, rawImagePath = path,
                        rotationDegrees = 0, cropQuadJson = "", filterType = FilterType.ORIGINAL.name
                    )
                    val newList = pages.toMutableList()
                    sorted.sortedDescending().forEach { newList.removeAt(it) }
                    newList.add(firstIdx.coerceAtMost(newList.size), newPage)
                    _uiState.update { it.copy(editingSessionPages = newList) }
                    onComplete(path)
                } else {
                    bitmaps.forEach { it.recycle() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to merge pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addBlankPage(docId: Long) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.Default) {
                val blankBmp = android.graphics.Bitmap.createBitmap(1240, 1754, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    blankBmp.eraseColor(android.graphics.Color.WHITE)
                    ImageProcessor.saveBitmapToFile(context, blankBmp, "blank_")
                } finally {
                    blankBmp.recycle()
                }
            }
            repository.addPageToDocument(docId, path, path)
        }
    }

    /** Explicit OCR for one page (OCR screen). Background analysis of whole documents uses the Worker. */
    fun runOcrInBackground(pageId: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Clear the previous page's result: the OCR screen never shows another page's text.
                _uiState.update { it.copy(isOcrLoading = true, ocrResult = null) }
                val page = repository.getPageById(pageId) ?: return@launch
                val language = _uiState.value.ocrLanguage
                if (language != OcrLanguage.ENGLISH && !TessDataManager.ensureArabic(context)) {
                    _events.send(UiEvent.ShowToast("Arabic text model unavailable — connect to the internet once to download it"))
                }
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 2000) ?: return@launch
                val result = try {
                    DocumentAiEngine.analyze(context, bmp, language)
                } finally {
                    bmp.recycle()
                }
                _uiState.update { it.copy(ocrResult = result) }
                // A failed OCR returns an error message as "text": never store it as page content.
                if (result.confidence <= 0f && result.fullText.startsWith("OCR Failed")) return@launch
                val current = repository.getPageById(pageId) ?: return@launch
                repository.updatePage(current.copy(ocrText = result.fullText))
                if (current.processedImagePath == page.processedImagePath && result.engine != "mlkit-fallback") {
                    OcrLayoutStore.save(page.processedImagePath, result.lines)
                }
                val doc = repository.getDocumentById(page.documentId)
                if (doc != null) {
                    val allText = repository.getPagesList(doc.id).map { it.ocrText }
                        .filter { it.isNotBlank() && !it.startsWith("OCR Failed") }.joinToString("\n")
                    val updatedDoc = doc.copy(
                        suggestedTitle = result.suggestedTitle,
                        category = if (result.detectedCategory != DocumentCategory.OTHER) result.detectedCategory.name else doc.category,
                        ocrText = allText
                    )
                    repository.updateDocument(updatedDoc)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update { it.copy(isOcrLoading = false) }
            }
        }
    }

    /** Prints the active page through the print framework (rendered off the main thread by the adapter). */
    fun printActivePage(context: Context) {
        val page = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        val title = _uiState.value.activeDocument?.title ?: "Document"
        PdfEngine.printScannedDocuments(context, "${title}_p${_uiState.value.selectedPageIndex + 1}", listOf(page.processedImagePath))
    }

    // ---------------------------------------------------------------- post-save share sheet (one-shot)
    private var pendingShareSheetDocId: Long = 0L

    /** Called right after a NEW scan is saved: the document screen opens with Save & Share options. */
    fun requestShareSheet(docId: Long) {
        pendingShareSheetDocId = docId
    }

    fun consumeShareSheetRequest(docId: Long): Boolean {
        if (pendingShareSheetDocId != docId || docId <= 0L) return false
        pendingShareSheetDocId = 0L
        return true
    }

    /** Deleting the last page removes the document (to Trash) instead of leaving an empty document. */
    fun moveDocumentToTrash(docId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            DocumentAnalysisWorker.cancel(context, docId)
            repository.moveToTrash(docId)
            onDone()
        }
    }

    fun duplicatePage(pageId: Long) {
        val page = _uiState.value.activePages.find { it.id == pageId } ?: return
        viewModelScope.launch {
            // A duplicate needs its own files: sharing paths would let one delete break the other.
            val (raw, proc) = withContext(Dispatchers.IO) {
                fun copyOf(path: String): String {
                    val src = File(path)
                    if (!src.exists()) return path
                    val dst = File(src.parentFile, "dup_${System.currentTimeMillis()}_${src.name}")
                    src.copyTo(dst, overwrite = true)
                    return dst.absolutePath
                }
                val r = copyOf(page.rawImagePath)
                QuadStore.load(page.rawImagePath)?.let { QuadStore.save(r, it) }
                val p = if (page.processedImagePath == page.rawImagePath) r else copyOf(page.processedImagePath)
                r to p
            }
            val count = repository.getPagesList(page.documentId).size
            repository.insertPage(page.copy(id = 0, pageIndex = count, rawImagePath = raw, processedImagePath = proc))
            repository.getDocumentById(page.documentId)?.let { repository.updateDocument(it.copy(pageCount = count + 1)) }
        }
    }

    fun updatePagesOrder(newOrder: List<PageEntity>) {
        viewModelScope.launch {
            val docId = newOrder.firstOrNull()?.documentId ?: return@launch
            repository.reorderPages(docId, newOrder)
        }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.activePages.toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices) return
        pages.add(toIndex, pages.removeAt(fromIndex))
        _uiState.update { it.copy(activePages = pages) }
        updatePagesOrder(pages)
    }

    fun saveAnnotations(
        paths: List<com.example.engine.annotation.DrawPath>,
        redactions: List<com.example.engine.annotation.RedactionRect>,
        signatures: List<com.example.engine.annotation.PlacedSignature>,
        placedTexts: List<com.example.engine.annotation.PlacedText>,
        shapes: List<com.example.engine.annotation.DrawShape>,
        brightness: Float,
        contrast: Float
    ) {
        val page = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val baseBmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
                if (baseBmp != null) {
                    val annotatedBmp = AnnotationEngine.burnAnnotationsIntoBitmap(
                        baseBmp, paths, redactions, signatures, placedTexts, shapes, brightness, contrast
                    )
                    val newPath = try {
                        ImageProcessor.saveBitmapToFile(context, annotatedBmp, "annotated_")
                    } finally {
                        if (annotatedBmp !== baseBmp) annotatedBmp.recycle()
                        baseBmp.recycle()
                    }
                    repository.updatePage(page.copy(processedImagePath = newPath))
                    OcrLayoutStore.copy(page.processedImagePath, newPath)
                    if (page.processedImagePath != page.rawImagePath) {
                        runCatching { File(page.processedImagePath).delete() }
                        OcrLayoutStore.delete(page.processedImagePath)
                    }
                    qualityCache.remove(page.id)
                    if (page.pageIndex == 0) refreshThumbnail(page.documentId, newPath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to save annotations"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun saveSignatureToVault(title: String, bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            val path = ImageProcessor.saveBitmapToFile(context, bitmap, "sig_vault_")
            repository.saveSignature(title, path)
        }
    }

    fun deletePageById(pageId: Long) {
        val page = _uiState.value.activePages.find { it.id == pageId } ?: return
        viewModelScope.launch {
            // repository.deletePage re-indexes the remaining pages, updates pageCount and the thumbnail.
            repository.deletePage(pageId, page.documentId)
            runCatching {
                QuadStore.delete(page.rawImagePath)
                File(page.rawImagePath).delete()
                if (page.processedImagePath != page.rawImagePath) File(page.processedImagePath).delete()
            }
            qualityCache.remove(pageId)
        }
    }

    fun applyFilterToAllPages(docId: Long, filter: FilterType) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Previously re-rendered raw WITHOUT the crop quad: every page lost its crop.
                for (page in repository.getPagesList(docId)) {
                    rerenderSavedPage(page, page.rotationDegrees, filter.takeIf { it != FilterType.ORIGINAL }, "proc_all")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to filter all pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun exportDocumentToPdf(config: PdfExportConfig, pageIds: Set<Long>? = null, onComplete: (File) -> Unit) {
        if (_uiState.value.isExportingPdf) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            try {
                val docId = _uiState.value.activeDocument?.id ?: return@launch
                val allPages = repository.getPagesList(docId)
                val targetPages = if (pageIds != null) allPages.filter { pageIds.contains(it.id) } else allPages
                if (targetPages.isEmpty()) {
                    _events.send(UiEvent.Error("No pages selected"))
                    return@launch
                }
                val pairs = targetPages.map { page ->
                    val img = if (page.processedImagePath.isNotBlank() && File(page.processedImagePath).exists()) page.processedImagePath else page.rawImagePath
                    Pair(img, page.ocrText)
                }
                val pdfFile = PdfEngine.generatePdf(context, pairs, config)
                if (!pdfFile.exists() || pdfFile.length() == 0L) {
                    throw IllegalStateException("PDF export produced an invalid file")
                }
                _uiState.update { it.copy(exportedPdfFile = pdfFile) }
                onComplete(pdfFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to export PDF: ${e.localizedMessage ?: "Unknown error"}"))
            } finally {
                _uiState.update { it.copy(isExportingPdf = false) }
            }
        }
    }

    fun mergePagesIntoSinglePage(
        selectedPageIds: List<Long>,
        mergedImagePath: String,
        replaceSelected: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val docId = _uiState.value.activeDocument?.id ?: return@launch
            if (selectedPageIds.isEmpty()) return@launch
            if (replaceSelected) {
                val firstId = selectedPageIds.first()
                repository.getPageById(firstId)?.let { page ->
                    repository.updatePage(
                        page.copy(
                            rawImagePath = mergedImagePath, processedImagePath = mergedImagePath,
                            cropQuadJson = "", rotationDegrees = 0, filterType = FilterType.ORIGINAL.name
                        )
                    )
                }
                for (i in 1 until selectedPageIds.size) repository.deletePage(selectedPageIds[i], docId)
            } else {
                repository.addPageToDocument(docId, mergedImagePath, mergedImagePath)
            }
            onComplete()
        }
    }

    fun setCompression(preset: CompressionPreset) {
        _uiState.update { it.copy(selectedCompression = preset) }
    }

    fun setOcrLanguage(language: com.example.engine.ocr.OcrLanguage) {
        _uiState.update { it.copy(ocrLanguage = language) }
    }

    fun setPdfPageSize(size: PageSizePreset) {
        _uiState.update { it.copy(defaultPdfPageSize = size) }
    }
}
