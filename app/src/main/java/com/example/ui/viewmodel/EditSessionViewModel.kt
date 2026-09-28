package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.example.data.model.*
import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.engine.annotation.AnnotationEngine
import com.example.engine.cv.ImageProcessingWorker
import com.example.engine.cv.ImageProcessor
import com.example.engine.cv.QualityReport
import com.example.engine.ocr.DocumentAiEngine
import com.example.engine.ocr.DocumentAnalysisResult
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.util.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    val batchQueueState: com.example.engine.cv.BatchQueueState = com.example.engine.cv.BatchQueueState()
)

class EditSessionViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val prefs = AppPreferences(context)
    private var originalSessionPages = listOf<com.example.data.model.PageEntity>()
    private val _uiState = MutableStateFlow(EditSessionUiState(
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression
    ))
    val uiState: StateFlow<EditSessionUiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>()
    val events = _events.receiveAsFlow()

    private val qualityCache = mutableMapOf<Long, QualityReport>()
    private val workManager = WorkManager.getInstance(context)
    val batchProcessingQueue = com.example.engine.cv.BatchProcessingQueue(viewModelScope)

    init {
        loadSignatures()
        viewModelScope.launch {
            batchProcessingQueue.queueState.collectLatest { queueState ->
                _uiState.update { it.copy(batchQueueState = queueState) }
            }
        }
    }

    private fun loadSignatures() {
        viewModelScope.launch {
            repository.getAllSignatures().collectLatest { sigs ->
                _uiState.update { it.copy(savedSignatures = sigs) }
            }
        }
    }

    fun updateActivePageProcessedImage(newPath: String) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx in pages.indices) {
            val page = pages[idx]
            viewModelScope.launch {
                val updatedPage = page.copy(processedImagePath = newPath)
                repository.updatePage(updatedPage)
                val newList = pages.toMutableList()
                newList[idx] = updatedPage
                _uiState.update { it.copy(activePages = newList) }
                
                if (idx == 0) {
                    val doc = repository.getDocumentById(page.documentId)
                    if (doc != null) {
                        // Delete old thumbnail if needed, though typically done in repo, here we just overwrite/create a new one
                        doc.thumbnailPath?.let { try { java.io.File(it).delete() } catch(e: Exception) {} }
                        val thumb = ImageProcessor.createThumbnail(context, newPath) ?: newPath
                        repository.updateDocument(doc.copy(thumbnailPath = thumb))
                    }
                }
            }
        }
    }

    fun replacePage(pageId: Long, newRawPath: String, newProcessedPath: String) {
        viewModelScope.launch {
            val page = _uiState.value.activePages.find { it.id == pageId } ?: return@launch
            val updatedPage = page.copy(
                rawImagePath = newRawPath,
                processedImagePath = newProcessedPath,
                cropQuadJson = "",
                rotationDegrees = 0
            )
            repository.updatePage(updatedPage)
            
            // Update thumbnail if it's the first page
            val idx = _uiState.value.activePages.indexOfFirst { it.id == pageId }
            if (idx == 0) {
                val doc = repository.getDocumentById(page.documentId)
                if (doc != null) {
                    doc.thumbnailPath?.let { try { java.io.File(it).delete() } catch(e: Exception) {} }
                    val thumb = ImageProcessor.createThumbnail(context, newProcessedPath) ?: newProcessedPath
                    repository.updateDocument(doc.copy(thumbnailPath = thumb))
                }
            }
            
            qualityCache.remove(pageId)
            loadDocument(page.documentId)
        }
    }
    fun loadDocument(docId: Long) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId)
            _uiState.update { it.copy(activeDocument = doc, selectedPageIndex = 0) }

            repository.getPagesForDocument(docId).collectLatest { pages ->
                _uiState.update { it.copy(activePages = pages) }
                // Evaluate quality for first page
                val firstPage = pages.firstOrNull()
                if (firstPage != null) {
                    evaluateQuality(firstPage)
                }
            }
        }
    }

    fun selectPageIndex(index: Int) {
        val pages = _uiState.value.activePages
        if (index in pages.indices) {
            _uiState.update { it.copy(selectedPageIndex = index) }
            viewModelScope.launch {
                evaluateQuality(pages[index])
            }
        }
    }

    private suspend fun evaluateQuality(page: PageEntity) {
        if (qualityCache.containsKey(page.id)) {
            _uiState.update { it.copy(currentQualityReport = qualityCache[page.id]) }
        } else {
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 500)
            if (bmp != null) {
                val report = ImageProcessor.analyzeQuality(bmp)
                qualityCache[page.id] = report
                _uiState.update { it.copy(currentQualityReport = report) }
                bmp.recycle()
            }
        }
    }

    fun startEditingSession(pages: List<PageEntity>) {
        originalSessionPages = pages
        _uiState.update { it.copy(editingSessionPages = pages, isEditingSession = true) }
    }

    fun endEditingSession() {
        val currentPages = _uiState.value.editingSessionPages
        currentPages.forEach { currentPage ->
            val originalPage = originalSessionPages.find { it.id == currentPage.id }
            if (originalPage != null && currentPage.processedImagePath != originalPage.processedImagePath) {
                java.io.File(currentPage.processedImagePath).delete()
            }
        }
        originalSessionPages = emptyList()
        _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
    }

    fun commitEditingSessionChanges(onComplete: () -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.editingSessionPages
            if (pages.isEmpty()) return@launch
            
            for (page in pages) {
                repository.updatePage(page)
                val originalPage = originalSessionPages.find { it.id == page.id }
                if (originalPage != null && originalPage.processedImagePath != page.processedImagePath) {
                    java.io.File(originalPage.processedImagePath).delete()
                }
            }
            
            originalSessionPages = emptyList()
            _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
            onComplete()
        }
    }

    fun updateEditingSessionPageProcessedImage(index: Int, newPath: String) {
        val pages = _uiState.value.editingSessionPages.toMutableList()
        if (index in pages.indices) {
            val page = pages[index]
            pages[index] = page.copy(processedImagePath = newPath)
            _uiState.update { it.copy(editingSessionPages = pages) }
        }
    }

    fun rotateEditingSessionPage(index: Int, clockwise: Boolean) {
        val pages = _uiState.value.editingSessionPages.toMutableList()
        if (index !in pages.indices) return
        val page = pages[index]
        
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_ROTATE_SESSION,
                ImageProcessingWorker.KEY_PAGE_ID to page.id,
                ImageProcessingWorker.KEY_CLOCKWISE to clockwise
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("rotate_session_$index", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { workInfo ->
                if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { it.copy(isLoading = false) }
                    val updatedPage = repository.getPageById(page.id)
                    if (updatedPage != null) {
                        val newPages = _uiState.value.editingSessionPages.toMutableList()
                        newPages[index] = updatedPage
                        _uiState.update { it.copy(editingSessionPages = newPages) }
                    }
                } else if (workInfo?.state == WorkInfo.State.FAILED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to rotate session page")) }
                }
            }
        }
    }

    fun batchAutoCropAllSessionPages() {
        val pages = if (_uiState.value.isEditingSession) _uiState.value.editingSessionPages else _uiState.value.activePages
        if (pages.isEmpty()) return

        val pagePairs = pages.map { Pair(it.rawImagePath.ifBlank { it.processedImagePath }, it.processedImagePath) }
        batchProcessingQueue.enqueueExistingPagePairs(
            context = context,
            pages = pagePairs,
            onPageUpdated = { index, newProcPath, _ ->
                if (_uiState.value.isEditingSession) {
                    updateEditingSessionPageProcessedImage(index, newProcPath)
                } else {
                    val page = pages.getOrNull(index)
                    if (page != null) {
                        viewModelScope.launch {
                            val updated = page.copy(processedImagePath = newProcPath)
                            repository.updatePage(updated)
                            val currentPages = _uiState.value.activePages.toMutableList()
                            if (index in currentPages.indices) {
                                currentPages[index] = updated
                                _uiState.update { it.copy(activePages = currentPages) }
                            }
                        }
                    }
                }
            }
        )
    }

    fun pauseBatchQueue() = batchProcessingQueue.pause()
    fun resumeBatchQueue() = batchProcessingQueue.resume()
    fun skipRemainingAutoCrop() = batchProcessingQueue.skipRemaining()
    fun cancelBatchQueue() = batchProcessingQueue.cancel()

    fun applyFilterToActivePage(filter: FilterType) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_CROP_WARP,
                ImageProcessingWorker.KEY_PAGE_ID to page.id,
                ImageProcessingWorker.KEY_QUAD_JSON to page.cropQuadJson,
                ImageProcessingWorker.KEY_FILTER_TYPE to filter.name
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("filter_active_${page.id}", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { info ->
                if (info?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { state -> state.copy(isLoading = false) }
                    qualityCache.remove(page.id)
                    loadDocument(page.documentId)
                } else if (info?.state == WorkInfo.State.FAILED) {
                    _uiState.update { state -> state.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to apply filter")) }
                }
            }
        }
    }

    fun rotateActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_ROTATE,
                ImageProcessingWorker.KEY_PAGE_ID to page.id,
                ImageProcessingWorker.KEY_ROTATION_DEGREES to 90
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("rotate_active_${page.id}", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { info ->
                if (info?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { state -> state.copy(isLoading = false) }
                    qualityCache.remove(page.id)
                    loadDocument(page.documentId)
                } else if (info?.state == WorkInfo.State.FAILED) {
                    _uiState.update { state -> state.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to rotate page")) }
                }
            }
        }
    }

    fun smartEnhanceActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
                if (bmp != null) {
                    val enhanced = ImageProcessor.applySmartEnhance(bmp)
                    val newPath = ImageProcessor.saveBitmapToFile(context, enhanced, "smart_enh_")
                    bmp.recycle()
                    enhanced.recycle()
                    
                    // Update in repository
                    repository.updatePage(page.copy(processedImagePath = newPath))
                    qualityCache.remove(page.id)
                    loadDocument(page.documentId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false) }
                _events.send(UiEvent.Error("Smart enhance failed"))
            }
        }
    }

    fun rotatePage(pageId: Long) {
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_ROTATE,
                ImageProcessingWorker.KEY_PAGE_ID to pageId,
                ImageProcessingWorker.KEY_ROTATION_DEGREES to 90
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("rotate_page_$pageId", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { info ->
                if (info?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { it.copy(isLoading = false) }
                    val page = repository.getPageById(pageId)
                    if (page != null) {
                        loadDocument(page.documentId)
                    }
                } else if (info?.state == WorkInfo.State.FAILED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to rotate page")) }
                }
            }
        }
    }

    fun deleteActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        val docId = page.documentId
        viewModelScope.launch {
            repository.deletePage(page.id, docId)
            val doc = repository.getDocumentById(docId)
            if (doc != null) {
                repository.updateDocument(doc.copy(pageCount = pages.size - 1))
            }
            loadDocument(docId)
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
            val allPages = mutableListOf<PageEntity>()
            for (id in docIds) {
                allPages.addAll(repository.getPagesForDocumentSync(id))
            }
            if (allPages.isEmpty()) {
                _uiState.update { it.copy(isExportingPdf = false) }
                return@launch
            }
            
            val config = PdfExportConfig(
                title = "Exported_PDF",
                pageSize = _uiState.value.defaultPdfPageSize,
                compression = _uiState.value.defaultPdfCompression
            )
            val pairs = allPages.map { page ->
                val img = if (page.processedImagePath.isNotBlank() && File(page.processedImagePath).exists()) {
                    page.processedImagePath
                } else {
                    page.rawImagePath
                }
                Pair(img, page.ocrText)
            }
            val pdfFile = PdfEngine.generatePdf(context, pairs, config)
            
            _uiState.update { it.copy(isExportingPdf = false, exportedPdfFile = pdfFile) }
            
            // Launch share intent
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", pdfFile)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "application/pdf"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share PDF via"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun runOcrOnActivePage(useDeepAi: Boolean = false) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        viewModelScope.launch {
            _uiState.update { it.copy(isOcrLoading = true) }
            try {
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
                if (bmp != null) {
                    val result = if (useDeepAi) {
                        DocumentAiEngine.analyzeWithGemini(bmp)
                    } else {
                        DocumentAiEngine.performOfflineOcr(bmp, _uiState.value.ocrLanguage)
                    }
                    _uiState.update { it.copy(ocrResult = result, isOcrLoading = false) }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isOcrLoading = false) }
                viewModelScope.launch { _events.send(UiEvent.Error("Analysis Failed: ${e.localizedMessage}")) }
            }
        }
    }

    fun saveDocumentToGallery(context: Context, docId: Long) {
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_SAVE_DOC_TO_GALLERY,
                ImageProcessingWorker.KEY_DOC_ID to docId
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("save_gallery_$docId", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { info ->
                if (info?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.ShowToast("Saved to Gallery")) }
                } else if (info?.state == WorkInfo.State.FAILED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to save to gallery")) }
                }
            }
        }
    }

    fun printActivePage(context: Context) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]
        viewModelScope.launch {
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath) ?: return@launch
            withContext(Dispatchers.Main) {
                try {
                    val printHelper = androidx.print.PrintHelper(context).apply {
                        scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
                    }
                    printHelper.printBitmap("Document_Page_${idx + 1}", bmp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun duplicatePage(pageId: Long) {
        val page = _uiState.value.activePages.find { it.id == pageId } ?: return
        viewModelScope.launch {
            val pages = _uiState.value.activePages
            val newPage = page.copy(id = 0, pageIndex = pages.size)
            repository.insertPage(newPage)
            val doc = repository.getDocumentById(page.documentId)
            if (doc != null) repository.updateDocument(doc.copy(pageCount = pages.size + 1))
            loadDocument(page.documentId)
        }
    }

    fun updatePagesOrder(newOrder: List<PageEntity>) {
        viewModelScope.launch {
            repository.updatePagesIndices(newOrder.mapIndexed { index, page -> page.copy(pageIndex = index) })
            _uiState.update { it.copy(activePages = newOrder) }
        }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.activePages.toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
        viewModelScope.launch {
            repository.updatePagesIndices(pages.mapIndexed { index, page -> page.copy(pageIndex = index) })
            _uiState.update { it.copy(activePages = pages) }
        }
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
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val baseBmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
                if (baseBmp != null) {
                    val annotatedBmp = AnnotationEngine.burnAnnotationsIntoBitmap(
                        baseBmp, paths, redactions, signatures, placedTexts, shapes, brightness, contrast
                    )
                    val newPath = ImageProcessor.saveBitmapToFile(context, annotatedBmp, "annotated_")
                    baseBmp.recycle()
                    annotatedBmp.recycle()

                    // Update repository
                    val updatedPage = page.copy(processedImagePath = newPath)
                    repository.updatePage(updatedPage)
                    
                    // Reload
                    loadDocument(page.documentId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false) }
                viewModelScope.launch { _events.send(UiEvent.Error("Failed to save annotations")) }
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
            repository.deletePage(pageId, page.documentId)
            loadDocument(page.documentId)
        }
    }

    fun applyFilterToAllPages(docId: Long, filter: FilterType) {
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_FILTER_ALL,
                ImageProcessingWorker.KEY_DOC_ID to docId,
                ImageProcessingWorker.KEY_FILTER_TYPE to filter.name
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("filter_all_$docId", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { info ->
                if (info?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.update { it.copy(isLoading = false) }
                    qualityCache.clear()
                    loadDocument(docId)
                } else if (info?.state == WorkInfo.State.FAILED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to filter all pages")) }
                }
            }
        }
    }

    fun exportDocumentToPdf(config: PdfExportConfig, pageIds: Set<Long>? = null, onComplete: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            val docId = _uiState.value.activeDocument?.id ?: return@launch
            val allPages = repository.getPagesList(docId)
            val targetPages = if (pageIds != null) {
                allPages.filter { pageIds.contains(it.id) }
            } else {
                allPages
            }
            
            if (targetPages.isEmpty()) {
                _uiState.update { it.copy(isExportingPdf = false) }
                return@launch
            }
            
            val pairs = targetPages.map { page ->
                val img = if (page.processedImagePath.isNotBlank() && File(page.processedImagePath).exists()) {
                    page.processedImagePath
                } else {
                    page.rawImagePath
                }
                Pair(img, page.ocrText)
            }
            val pdfFile = PdfEngine.generatePdf(context, pairs, config)
            
            _uiState.update { it.copy(isExportingPdf = false, exportedPdfFile = pdfFile) }
            onComplete(pdfFile)
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
            if (replaceSelected) {
                // Keep first page, update it, delete others
                val firstId = selectedPageIds.first()
                val page = repository.getPageById(firstId)
                if (page != null) {
                    repository.updatePage(page.copy(processedImagePath = mergedImagePath))
                }
                for (i in 1 until selectedPageIds.size) {
                    repository.deletePage(selectedPageIds[i], docId)
                }
            } else {
                // Add as new page
                repository.addPageToDocument(docId, mergedImagePath, mergedImagePath)
            }
            loadDocument(docId)
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
