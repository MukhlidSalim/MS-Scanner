package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.engine.annotation.AnnotationEngine
import com.example.engine.cv.DocumentQuad
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
            
            // Trigger background processing for first page
            pages.firstOrNull()?.id?.let { firstPageId ->
                runOcrInBackground(firstPageId)
            }
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

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val source = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 2048)
                    ?: throw IllegalStateException("Unable to load page image")
                val degrees = if (clockwise) 90 else -90
                val rotated = ImageProcessor.rotateBitmap(source, degrees)
                val newPath = ImageProcessor.saveBitmapToFile(context, rotated, "rot_session_")

                if (rotated !== source) source.recycle()
                rotated.recycle()

                val updatedPage = page.copy(
                    processedImagePath = newPath,
                    rotationDegrees = ((page.rotationDegrees + degrees) % 360 + 360) % 360
                )
                repository.updatePage(updatedPage)
                pages[index] = updatedPage
                _uiState.update { it.copy(editingSessionPages = pages) }
                qualityCache.remove(page.id)
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to rotate session page"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun batchAutoCropAllSessionPages() {
        val pages = if (_uiState.value.isEditingSession) _uiState.value.editingSessionPages else _uiState.value.activePages
        if (pages.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                for ((index, page) in pages.withIndex()) {
                    val rawPath = page.rawImagePath.ifBlank { page.processedImagePath }
                    val rawBmp = ImageProcessor.loadBitmapFromFile(rawPath, 2048) ?: continue
                    try {
                        val quad = ImageProcessor.detectDocumentQuad(rawBmp)
                        val valid = ImageProcessor.isQuadValid(quad)
                        val cropped = if (valid) ImageProcessor.applyPerspectiveWarp(rawBmp, quad)
                                      else rawBmp.copy(rawBmp.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
                        try {
                            val filtered = ImageProcessor.applyFilter(cropped, FilterType.AUTO)
                            try {
                                val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "batch_recrop_")
                                val updated = page.copy(
                                    processedImagePath = newPath,
                                    cropQuadJson = if (valid) quad.toJson() else page.cropQuadJson,
                                    filterType = FilterType.AUTO.name
                                )
                                if (_uiState.value.isEditingSession) {
                                    val current = _uiState.value.editingSessionPages.toMutableList()
                                    if (index in current.indices) current[index] = updated
                                    _uiState.update { it.copy(editingSessionPages = current) }
                                } else {
                                    repository.updatePage(updated)
                                    val current = _uiState.value.activePages.toMutableList()
                                    if (index in current.indices) current[index] = updated
                                    _uiState.update { it.copy(activePages = current) }
                                }
                                qualityCache.remove(page.id)
                            } finally {
                                if (filtered !== cropped) filtered.recycle()
                            }
                        } finally {
                            if (cropped !== rawBmp) cropped.recycle()
                        }
                    } finally {
                        rawBmp.recycle()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to auto-crop pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                if (_uiState.value.activeDocument?.id != null && !_uiState.value.isEditingSession) {
                    loadDocument(_uiState.value.activeDocument!!.id)
                }
            }
        }
    }

    fun applyFilterToActivePage(filter: FilterType) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val raw = ImageProcessor.loadBitmapFromFile(page.rawImagePath, 2048)
                    ?: throw IllegalStateException("Unable to load original page")
                try {
                    val quad = DocumentQuad.fromJson(page.cropQuadJson)
                    val warped = ImageProcessor.applyPerspectiveWarp(raw, quad)
                    try {
                        val rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
                        try {
                            val filtered = ImageProcessor.applyFilter(rotated, filter)
                            try {
                                val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "filter_active_")
                                val updated = page.copy(processedImagePath = newPath, filterType = filter.name)
                                repository.updatePage(updated)
                                qualityCache.remove(page.id)
                                loadDocument(page.documentId)
                            } finally { if (filtered !== rotated) filtered.recycle() }
                        } finally { if (rotated !== warped) rotated.recycle() }
                    } finally { if (warped !== raw) warped.recycle() }
                } finally { raw.recycle() }
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to apply filter"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun rotateActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        rotatePage(pages[idx].id)
    }

    fun smartEnhanceActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        
        viewModelScope.launch(Dispatchers.Default) {
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
                _events.send(UiEvent.Error("Smart enhance failed"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun rotatePage(pageId: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val page = repository.getPageById(pageId) ?: return@launch
                val raw = ImageProcessor.loadBitmapFromFile(page.rawImagePath, 2048)
                    ?: throw IllegalStateException("Unable to load original page")
                try {
                    val quad = DocumentQuad.fromJson(page.cropQuadJson)
                    val warped = ImageProcessor.applyPerspectiveWarp(raw, quad)
                    try {
                        val newRotation = ((page.rotationDegrees + 90) % 360 + 360) % 360
                        val rotated = ImageProcessor.rotateBitmap(warped, newRotation)
                        try {
                            val filter = runCatching { FilterType.valueOf(page.filterType) }.getOrDefault(FilterType.AUTO)
                            val filtered = ImageProcessor.applyFilter(rotated, filter)
                            try {
                                val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "rotate_")
                                repository.updatePage(page.copy(processedImagePath = newPath, rotationDegrees = newRotation))
                                qualityCache.remove(pageId)
                            } finally { if (filtered !== rotated) filtered.recycle() }
                        } finally { if (rotated !== warped) rotated.recycle() }
                    } finally { if (warped !== raw) warped.recycle() }
                } finally { raw.recycle() }
                loadDocument(page.documentId)
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

    fun addBlankSessionPage() {
        viewModelScope.launch(Dispatchers.IO) {
            val blankBmp = android.graphics.Bitmap.createBitmap(2480, 3508, android.graphics.Bitmap.Config.ARGB_8888)
            blankBmp.eraseColor(android.graphics.Color.WHITE)
            val path = ImageProcessor.saveBitmapToFile(context, blankBmp, "blank_session_")
            blankBmp.recycle()
            
            withContext(Dispatchers.Main) {
                val newPage = PageEntity(
                    documentId = _uiState.value.activeDocument?.id ?: 0L,
                    rawImagePath = path,
                    processedImagePath = path,
                    pageIndex = _uiState.value.editingSessionPages.size
                )
                val newList = _uiState.value.editingSessionPages.toMutableList()
                newList.add(newPage)
                _uiState.update { it.copy(editingSessionPages = newList) }
            }
        }
    }

    fun mergeSessionPages(indices: Set<Int>, onComplete: (String) -> Unit) {
        val pages = _uiState.value.editingSessionPages
        val selectedPages = indices.mapNotNull { pages.getOrNull(it) }
        if (selectedPages.size < 2) return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bitmaps = selectedPages.mapNotNull { ImageProcessor.loadBitmapFromFile(it.processedImagePath) }
                if (bitmaps.size >= 2) {
                    val merged = ImageProcessor.mergeBitmapsVertical(bitmaps)
                    val path = ImageProcessor.saveBitmapToFile(context, merged, "merged_session_")
                    bitmaps.forEach { it.recycle() }
                    merged.recycle()

                    withContext(Dispatchers.Main) {
                        val firstIdx = indices.minOrNull() ?: 0
                        val newPage = selectedPages.first().copy(processedImagePath = path, rawImagePath = path)
                        
                        val newList = pages.toMutableList()
                        // Remove all selected indices (reverse order to keep indices valid)
                        indices.sortedDescending().forEach { newList.removeAt(it) }
                        // Insert merged at first position
                        newList.add(firstIdx.coerceAtMost(newList.size), newPage)
                        
                        _uiState.update { it.copy(editingSessionPages = newList) }
                        onComplete(path)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addBlankPage(docId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val blankBmp = android.graphics.Bitmap.createBitmap(2480, 3508, android.graphics.Bitmap.Config.ARGB_8888)
            blankBmp.eraseColor(android.graphics.Color.WHITE)
            val path = ImageProcessor.saveBitmapToFile(context, blankBmp, "blank_")
            blankBmp.recycle()
            repository.addPageToDocument(docId, path, path)
            loadDocument(docId)
        }
    }

    fun runOcrInBackground(pageId: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(isOcrLoading = true) }
                val page = repository.getPageById(pageId) ?: return@launch
                
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 1500) ?: return@launch
                val result = DocumentAiEngine.performOfflineOcr(bmp, _uiState.value.ocrLanguage)
                bmp.recycle()
                
                _uiState.update { it.copy(ocrResult = result, isOcrLoading = false) }

                repository.updatePage(page.copy(
                    ocrText = result.fullText
                ))
                
                // Update document with suggested title and category
                val doc = repository.getDocumentById(page.documentId)
                if (doc != null) {
                    val updatedDoc = doc.copy(
                        suggestedTitle = result.suggestedTitle,
                        category = result.detectedCategory.name
                    )
                    // If doc title is generic, update it
                    if (doc.title.startsWith("Doc_") || doc.title.isBlank()) {
                        repository.updateDocument(updatedDoc.copy(title = result.suggestedTitle))
                    } else {
                        repository.updateDocument(updatedDoc)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isOcrLoading = false) }
            }
        }
    }

    fun printActivePage(context: Context) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.Default) {
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
            repository.deletePage(pageId, page.documentId)
            loadDocument(page.documentId)
        }
    }

    fun applyFilterToAllPages(docId: Long, filter: FilterType) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val pages = repository.getPagesList(docId)
                for (page in pages) {
                    val raw = ImageProcessor.loadBitmapFromFile(page.rawImagePath, 2048) ?: continue
                    try {
                        val rotated = ImageProcessor.rotateBitmap(raw, page.rotationDegrees)
                        try {
                            val filtered = ImageProcessor.applyFilter(rotated, filter)
                            try {
                                val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "proc_all_")
                                val oldFile = File(page.processedImagePath)
                                if (oldFile.exists() && oldFile.absolutePath != page.rawImagePath) oldFile.delete()
                                repository.updatePage(page.copy(filterType = filter.name, processedImagePath = newPath))
                            } finally { if (filtered !== rotated) filtered.recycle() }
                        } finally { if (rotated !== raw) rotated.recycle() }
                    } finally { raw.recycle() }
                }
                qualityCache.clear()
                loadDocument(docId)
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to filter all pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun exportDocumentToPdf(config: PdfExportConfig, pageIds: Set<Long>? = null, onComplete: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExportingPdf = true) }
            try {
                val docId = _uiState.value.activeDocument?.id ?: return@launch
                val allPages = repository.getPagesList(docId)
                val targetPages = if (pageIds != null) {
                    allPages.filter { pageIds.contains(it.id) }
                } else {
                    allPages
                }

                if (targetPages.isEmpty()) {
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
                if (!pdfFile.exists() || pdfFile.length() == 0L) {
                    throw IllegalStateException("PDF export produced an invalid file")
                }

                _uiState.update { it.copy(exportedPdfFile = pdfFile) }
                withContext(Dispatchers.Main) { onComplete(pdfFile) }
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
