package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.DocumentRepository
import com.example.data.repository.StorageStats
import com.example.engine.annotation.*
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.engine.cv.QualityReport
import com.example.engine.ocr.DocumentAiEngine
import com.example.engine.ocr.DocumentAnalysisResult
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DocumentUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val favoriteDocuments: List<DocumentEntity> = emptyList(),
    val trashDocuments: List<DocumentEntity> = emptyList(),
    val folders: List<String> = emptyList(),
    val selectedFolder: String = "ALL",
    val selectedCategory: DocumentCategory = DocumentCategory.ALL,
    val searchQuery: String = "",
    val activeDocument: DocumentEntity? = null,
    val activePages: List<PageEntity> = emptyList(),
    val selectedPageIndex: Int = 0,
    val currentQualityReport: QualityReport? = null,
    val isOcrLoading: Boolean = false,
    val ocrResult: DocumentAnalysisResult? = null,
    val exportedPdfFile: File? = null,
    val isExportingPdf: Boolean = false,
    val storageStats: StorageStats? = null,
    val savedSignatures: List<SignatureEntity> = emptyList(),
    val isAppLocked: Boolean = false,
    val userPin: String = "",
    val hasPinConfigured: Boolean = false
)

class DocumentViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    init {
        loadAllDocuments()
        loadFolders()
        loadSignatures()
        refreshStorageStats()
    }

    private fun loadAllDocuments() {
        viewModelScope.launch {
            repository.getAllDocuments().collectLatest { docs ->
                _uiState.update { it.copy(documents = docs) }
            }
        }
        viewModelScope.launch {
            repository.getFavoriteDocuments().collectLatest { favs ->
                _uiState.update { it.copy(favoriteDocuments = favs) }
            }
        }
        viewModelScope.launch {
            repository.getTrashDocuments().collectLatest { trash ->
                _uiState.update { it.copy(trashDocuments = trash) }
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            repository.getAllFolders().collectLatest { foldersList ->
                _uiState.update { it.copy(folders = foldersList) }
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

    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = repository.getStorageStats()
            val totalDocs = _uiState.value.documents.size
            val totalPages = _uiState.value.documents.sumOf { it.pageCount }
            _uiState.update {
                it.copy(
                    storageStats = stats.copy(
                        totalDocumentsCount = totalDocs,
                        totalPagesCount = totalPages,
                        trashCount = it.trashDocuments.size
                    )
                )
            }
        }
    }

    fun importPagesAsDocument(pages: List<Pair<String, String>>, onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val docId = repository.createDocumentWithPages(
                title = "Imported Doc",
                pages = pages
            )
            refreshStorageStats()
            onComplete(docId)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            if (query.isBlank()) {
                repository.getAllDocuments().first().let { docs ->
                    _uiState.update { it.copy(documents = docs) }
                }
            } else {
                repository.searchDocuments(query).collectLatest { searchResults ->
                    _uiState.update { it.copy(documents = searchResults) }
                }
            }
        }
    }

    fun filterByFolder(folder: String) {
        _uiState.update { it.copy(selectedFolder = folder) }
        viewModelScope.launch {
            if (folder == "ALL") {
                repository.getAllDocuments().collectLatest { docs ->
                    _uiState.update { it.copy(documents = docs) }
                }
            } else {
                repository.getDocumentsByFolder(folder).collectLatest { docs ->
                    _uiState.update { it.copy(documents = docs) }
                }
            }
        }
    }

    fun filterByCategory(cat: DocumentCategory) {
        _uiState.update { it.copy(selectedCategory = cat) }
        viewModelScope.launch {
            if (cat == DocumentCategory.ALL) {
                repository.getAllDocuments().collectLatest { docs ->
                    _uiState.update { it.copy(documents = docs) }
                }
            } else {
                repository.getDocumentsByCategory(cat.name).collectLatest { docs ->
                    _uiState.update { it.copy(documents = docs) }
                }
            }
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
                    val bmp = ImageProcessor.loadBitmapFromFile(firstPage.processedImagePath, 500)
                    if (bmp != null) {
                        val report = ImageProcessor.analyzeQuality(bmp)
                        _uiState.update { it.copy(currentQualityReport = report) }
                    }
                }
            }
        }
    }

    fun selectPageIndex(index: Int) {
        val pages = _uiState.value.activePages
        if (index in pages.indices) {
            _uiState.update { it.copy(selectedPageIndex = index) }
            viewModelScope.launch {
                val page = pages[index]
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 500)
                if (bmp != null) {
                    val report = ImageProcessor.analyzeQuality(bmp)
                    _uiState.update { it.copy(currentQualityReport = report) }
                    bmp.recycle()
                }
            }
        }
    }

    fun toggleFavorite(docId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(docId)
        }
    }

    fun moveToTrash(docId: Long) {
        viewModelScope.launch {
            repository.moveToTrash(docId)
            refreshStorageStats()
        }
    }

    fun restoreFromTrash(docId: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(docId)
            refreshStorageStats()
        }
    }

    fun deletePermanently(docId: Long) {
        viewModelScope.launch {
            repository.deleteDocumentPermanently(docId)
            refreshStorageStats()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
            refreshStorageStats()
        }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        val pages = _uiState.value.activePages.toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
        viewModelScope.launch {
            repository.updatePagesIndices(pages.mapIndexed { index, page -> page.copy(pageIndex = index) })
        }
    }

    fun duplicateActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]
        viewModelScope.launch {
            val newPage = page.copy(id = 0, pageIndex = pages.size)
            repository.insertPage(newPage)
            val doc = repository.getDocumentById(page.documentId)
            if (doc != null) repository.updateDocument(doc.copy(pageCount = pages.size + 1))
            refreshStorageStats()
            loadDocument(page.documentId)
        }
    }

    fun addPagesToCurrentDocument(newPages: List<Pair<String, String>>) {
        viewModelScope.launch {
            val doc = _uiState.value.activeDocument ?: return@launch
            for (pagePair in newPages) {
                val newPage = com.example.data.model.PageEntity(
                    documentId = doc.id,
                    pageIndex = _uiState.value.activePages.size,
                    rawImagePath = pagePair.first,
                    processedImagePath = pagePair.second
                )
                repository.insertPage(newPage)
                _uiState.value = _uiState.value.copy(
                    activePages = _uiState.value.activePages + newPage
                )
            }
            repository.updateDocument(doc.copy(pageCount = _uiState.value.activePages.size))
            refreshStorageStats()
            loadDocument(doc.id)
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
            try {
                java.io.File(page.rawImagePath).delete()
                java.io.File(page.processedImagePath).delete()
            } catch (e: Exception) { /* ignore */ }

            // If it was the last page, move the whole doc to trash
            if (pages.size == 1) {
                repository.moveToTrash(docId)
            } else {
                // Update page count
                val doc = repository.getDocumentById(docId)
                if (doc != null) {
                    repository.updateDocument(doc.copy(pageCount = pages.size - 1))
                }
            }
            refreshStorageStats()
        }
    }

    fun renameDocument(docId: Long, newTitle: String) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            repository.updateDocument(doc.copy(title = newTitle))
            _uiState.update { it.copy(activeDocument = doc.copy(title = newTitle)) }
        }
    }

    fun changeDocumentFolder(docId: Long, folder: String) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            repository.updateDocument(doc.copy(folderName = folder))
        }
    }

    /**
     * Applies filter to page and saves processed file
     */
    fun applyFilterToActivePage(filter: FilterType) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        viewModelScope.launch {
            val rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@launch
            val quad = DocumentQuad.fromJson(page.cropQuadJson)
            val warped = ImageProcessor.warpPerspective(rawBmp, quad)
            val rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
            val filtered = ImageProcessor.applyFilter(rotated, filter)
            val newProcessedPath = ImageProcessor.saveBitmapToFile(context, filtered, "flt_")
            
            // Free memory
            if (rawBmp != warped) rawBmp.recycle()
            if (warped != rotated) warped.recycle()
            if (rotated != filtered) rotated.recycle()
            filtered.recycle()

            val updated = page.copy(
                processedImagePath = newProcessedPath,
                filterType = filter.name
            )
            repository.updatePage(updated)
        }
    }

    /**
     * Rotates current page by 90 degrees
     */
    fun rotateActivePage() {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        val newRotation = (page.rotationDegrees + 90) % 360
        viewModelScope.launch {
            val rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@launch
            val quad = DocumentQuad.fromJson(page.cropQuadJson)
            val warped = ImageProcessor.warpPerspective(rawBmp, quad)
            val rotated = ImageProcessor.rotateBitmap(warped, newRotation)
            val filter = try { FilterType.valueOf(page.filterType) } catch (e: Exception) { FilterType.MAGIC }
            val filtered = ImageProcessor.applyFilter(rotated, filter)
            val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "rot_")
            
            // Free memory
            if (rawBmp != warped) rawBmp.recycle()
            if (warped != rotated) warped.recycle()
            if (rotated != filtered) rotated.recycle()
            filtered.recycle()

            val updated = page.copy(
                processedImagePath = newPath,
                rotationDegrees = newRotation
            )
            repository.updatePage(updated)
        }
    }

    /**
     * Updates perspective quad and warps bitmap
     */
    fun updatePageQuadAndWarp(pageId: Long, quad: DocumentQuad) {
        viewModelScope.launch {
            val page = _uiState.value.activePages.find { it.id == pageId } ?: return@launch
            val rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@launch
            val warped = ImageProcessor.warpPerspective(rawBmp, quad)
            val rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
            val filter = try { FilterType.valueOf(page.filterType) } catch (e: Exception) { FilterType.MAGIC }
            val filtered = ImageProcessor.applyFilter(rotated, filter)
            val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "crop_")
            
            // Free memory
            if (rawBmp != warped) rawBmp.recycle()
            if (warped != rotated) warped.recycle()
            if (rotated != filtered) rotated.recycle()
            filtered.recycle()

            val updated = page.copy(
                cropQuadJson = quad.toJson(),
                processedImagePath = newPath
            )
            repository.updatePage(updated)
        }
    }

    /**
     * Performs OCR & AI Intelligence on the active page
     */
    fun runOcrOnActivePage(useDeepAi: Boolean = true) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]

        viewModelScope.launch {
            _uiState.update { it.copy(isOcrLoading = true) }
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath)
            if (bmp != null) {
                val result = if (useDeepAi) {
                    DocumentAiEngine.analyzeWithGemini(bmp)
                } else {
                    DocumentAiEngine.performOfflineOcr(bmp)
                }
                bmp.recycle()

                _uiState.update {
                    it.copy(
                        isOcrLoading = false,
                        ocrResult = result
                    )
                }

                // Save OCR to page
                val updated = page.copy(
                    ocrText = result.fullText
                )
                repository.updatePage(updated)

                // If document doesn't have a good title yet, suggest the AI one
                val doc = _uiState.value.activeDocument
                if (doc != null && doc.title.startsWith("Doc_") && result.suggestedTitle.isNotBlank()) {
                    repository.updateDocument(
                        doc.copy(
                            title = result.suggestedTitle,
                            category = result.detectedCategory.name
                        )
                    )
                }
            } else {
                _uiState.update { it.copy(isOcrLoading = false) }
            }
        }
    }

    /**
     * Saves annotations/signature onto current page
     */
    fun saveAnnotations(
        paths: List<DrawPath>,
        redactions: List<RedactionRect>,
        signatures: List<PlacedSignature>
    ) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]

        viewModelScope.launch {
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath) ?: return@launch
            val burned = AnnotationEngine.burnAnnotationsIntoBitmap(bmp, paths, redactions, signatures)
            val newPath = ImageProcessor.saveBitmapToFile(context, burned, "ann_")

            // Free memory
            if (bmp != burned) bmp.recycle()
            burned.recycle()

            val updated = page.copy(processedImagePath = newPath)
            repository.updatePage(updated)
        }
    }

    /**
     * Exports current active document to PDF
     */
    fun exportDocumentToPdf(config: PdfExportConfig, onComplete: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            val pages = _uiState.value.activePages
            val pairs = pages.map { Pair(it.processedImagePath, it.ocrText) }

            val pdfFile = PdfEngine.generatePdf(context, pairs, config)
            _uiState.update {
                it.copy(
                    isExportingPdf = false,
                    exportedPdfFile = pdfFile
                )
            }
            onComplete(pdfFile)
        }
    }

    fun saveSignatureToVault(title: String, bitmap: Bitmap) {
        viewModelScope.launch {
            val path = AnnotationEngine.saveSignatureBitmap(context, bitmap)
            repository.saveSignature(title, path)
        }
    }

    fun deleteSignatureFromVault(id: Long) {
        viewModelScope.launch {
            repository.deleteSignature(id)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refreshStorageStats()
        }
    }

    fun setPin(pin: String) {
        _uiState.update { it.copy(userPin = pin, hasPinConfigured = pin.length == 4) }
    }

    fun verifyPin(pin: String): Boolean {
        return if (_uiState.value.userPin == pin) {
            _uiState.update { it.copy(isAppLocked = false) }
            true
        } else {
            false
        }
    }

    fun lockApp() {
        if (_uiState.value.hasPinConfigured) {
            _uiState.update { it.copy(isAppLocked = true) }
        }
    }
}
