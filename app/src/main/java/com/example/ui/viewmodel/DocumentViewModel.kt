package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackupManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import com.example.data.model.*
import com.example.data.repository.DocumentRepository
import com.example.data.repository.StorageStats
import com.example.engine.annotation.*
import com.example.engine.cv.DocumentQuad
import android.widget.Toast
import com.example.R
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
    val hasPinConfigured: Boolean = false,
    val themeMode: String = "System",
    val defaultPdfPageSize: com.example.data.model.PageSizePreset = com.example.data.model.PageSizePreset.A4,
    val defaultPdfCompression: com.example.data.model.CompressionPreset = com.example.data.model.CompressionPreset.HIGH,
    val sortMode: SortMode = SortMode.NEWEST,
    val isLoading: Boolean = false
)

class DocumentViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val prefs = com.example.data.repository.AppPreferences(context)
    private val _uiState = MutableStateFlow(DocumentUiState(
        userPin = prefs.userPin,
        hasPinConfigured = prefs.userPin.length == 4,
        themeMode = prefs.themeMode,
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression
    ))
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    
    private data class FilterState(
        val folder: String = "ALL",
        val category: DocumentCategory = DocumentCategory.ALL,
        val query: String = "",
        val sort: SortMode = SortMode.NEWEST
    )
    private val filterTrigger = MutableStateFlow(FilterState())
    private val qualityCache = mutableMapOf<Long, QualityReport>()

    init {
        setupDocumentStream()
        loadFolders()
        loadSignatures()
        refreshStorageStats()
    }

    private fun setupDocumentStream() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.getAllDocuments(),
                filterTrigger
            ) { docs, filter ->
                var filtered = docs
                if (filter.folder != "ALL") {
                    filtered = filtered.filter { it.folderName == filter.folder }
                }
                if (filter.query.isNotBlank()) {
                    filtered = filtered.filter { it.title.contains(filter.query, ignoreCase = true) }
                }
                applySorting(filtered, filter.sort)
            }.collectLatest { sortedAndFiltered ->
                _uiState.update { it.copy(documents = sortedAndFiltered) }
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
            _uiState.update { it.copy(storageStats = stats) }
        }
    }

    fun importPagesAsDocument(pages: List<Pair<String, String>>, onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val folder = if (_uiState.value.selectedFolder == "ALL") "Default" else _uiState.value.selectedFolder
            val docId = repository.createDocumentWithPages(
                title = "Imported Doc",
                folderName = folder,
                pages = pages
            )
            refreshStorageStats()
            onComplete(docId)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        filterTrigger.value = filterTrigger.value.copy(query = query)
    }

    
    fun renameFolder(oldName: String, newName: String) {
        viewModelScope.launch {
            repository.renameFolder(oldName, newName)
            if (_uiState.value.selectedFolder == oldName) {
                filterByFolder(newName)
            }
        }
    }

    fun deleteFolder(folderName: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderName)
            if (_uiState.value.selectedFolder == folderName) {
                filterByFolder("ALL")
            }
        }
    }

    fun filterByFolder(folder: String) {
        _uiState.update { it.copy(selectedFolder = folder) }
        filterTrigger.value = filterTrigger.value.copy(folder = folder)
    }

    private fun applySorting(docs: List<DocumentEntity>, mode: SortMode): List<DocumentEntity> {
        return when (mode) {
            SortMode.NEWEST -> docs.sortedByDescending { it.updatedAt }
            SortMode.OLDEST -> docs.sortedBy { it.updatedAt }
            SortMode.NAME_AZ -> docs.sortedBy { it.title.lowercase() }
            SortMode.NAME_ZA -> docs.sortedByDescending { it.title.lowercase() }
            SortMode.SIZE_LARGEST -> docs.sortedByDescending { it.sizeBytes }
            SortMode.PAGE_COUNT -> docs.sortedByDescending { it.pageCount }
        }
    }

    fun setSortMode(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        filterTrigger.value = filterTrigger.value.copy(sort = mode)
    }

    fun filterByCategory(cat: DocumentCategory) {
        _uiState.update { it.copy(selectedCategory = cat) }
        filterTrigger.value = filterTrigger.value.copy(category = cat)
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
                    if (qualityCache.containsKey(firstPage.id)) {
                        _uiState.update { it.copy(currentQualityReport = qualityCache[firstPage.id]) }
                    } else {
                        val bmp = ImageProcessor.loadBitmapFromFile(firstPage.processedImagePath, 500)
                        if (bmp != null) {
                            val report = ImageProcessor.analyzeQuality(bmp)
                            qualityCache[firstPage.id] = report
                            _uiState.update { it.copy(currentQualityReport = report) }
                            bmp.recycle()
                        }
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
            var pageCount = doc.pageCount
            for (pagePair in newPages) {
                val newPage = com.example.data.model.PageEntity(
                    documentId = doc.id,
                    pageIndex = pageCount,
                    rawImagePath = pagePair.first,
                    processedImagePath = pagePair.second
                )
                repository.insertPage(newPage)
                pageCount++
            }
            repository.updateDocument(doc.copy(pageCount = pageCount))
            // Refresh from DB
            val dbPages = repository.getPagesList(doc.id)
            _uiState.update { it.copy(activePages = dbPages) }
        }
    }

    
    fun mergeDocuments(docIds: List<Long>) {
        if (docIds.size < 2) return
        viewModelScope.launch {
            val docs = docIds.mapNotNull { id -> _uiState.value.documents.find { it.id == id } }
            if (docs.size < 2) return@launch
            
            val allPages = mutableListOf<com.example.data.model.PageEntity>()
            for (doc in docs) {
                allPages.addAll(repository.getPagesForDocumentSync(doc.id))
            }
            
            val newTitle = docs.first().title + "_Merged"
            val newDocId = repository.createDocumentWithPages(
                title = newTitle,
                folderName = _uiState.value.selectedFolder.takeIf { it != "ALL" } ?: "Default",
                category = "OTHER",
                pages = allPages.map { Pair(it.rawImagePath, it.processedImagePath) }
            )
            refreshStorageStats()
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

        fun applyFilterToAllPages(context: Context, filter: FilterType) {
        viewModelScope.launch {
            try {
                val docId = _uiState.value.activeDocument?.id ?: return@launch
                val pages = repository.getPagesList(docId)
                if (pages.isEmpty()) return@launch

                val updatedPages = pages.map { page ->
                    var rawBitmap: android.graphics.Bitmap? = null
                    var rotatedRaw: android.graphics.Bitmap? = null
                    var filtered: android.graphics.Bitmap? = null
                    var newProcessedPath: String = page.processedImagePath
                    
                    try {
                        rawBitmap = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@map page
                        rotatedRaw = ImageProcessor.rotateBitmap(rawBitmap, page.rotationDegrees)
                        filtered = ImageProcessor.applyFilter(rotatedRaw, filter)
                        newProcessedPath = ImageProcessor.saveBitmapToFile(context, filtered, "proc_all_")
                        
                        val oldFile = java.io.File(page.processedImagePath)
                        if (oldFile.exists() && oldFile.absolutePath != page.rawImagePath) {
                            oldFile.delete()
                        }
                    } finally {
                        if (rawBitmap != rotatedRaw) rawBitmap?.recycle()
                        if (rotatedRaw != filtered) rotatedRaw?.recycle()
                        filtered?.recycle()
                    }
                    
                    page.copy(filterType = filter.name, processedImagePath = newProcessedPath)
                }

                repository.updatePages(updatedPages)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

        fun applyFilterToActivePage(filter: FilterType) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        viewModelScope.launch {
            var rawBmp: android.graphics.Bitmap? = null
            var warped: android.graphics.Bitmap? = null
            var rotated: android.graphics.Bitmap? = null
            var filtered: android.graphics.Bitmap? = null
            try {
                rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@launch
                val quad = DocumentQuad.fromJson(page.cropQuadJson)
                warped = ImageProcessor.warpPerspective(rawBmp, quad)
                rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
                filtered = ImageProcessor.applyFilter(rotated, filter)
                val newProcessedPath = ImageProcessor.saveBitmapToFile(context, filtered, "flt_")
                
                qualityCache.remove(page.id)
                val updated = page.copy(
                    processedImagePath = newProcessedPath,
                    filterType = filter.name
                )
                repository.updatePage(updated)
            } finally {
                if (rawBmp != warped) rawBmp?.recycle()
                if (warped != rotated) warped?.recycle()
                if (rotated != filtered) rotated?.recycle()
                filtered?.recycle()
            }
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

            qualityCache.remove(page.id)
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

            qualityCache.remove(page.id)
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
                qualityCache.remove(page.id)
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
        signatures: List<PlacedSignature>,
        placedTexts: List<com.example.engine.annotation.PlacedText> = emptyList(),
        brightness: Float = 0f,
        contrast: Float = 1f
    ) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return
        val page = pages[idx]

        viewModelScope.launch {
            val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath) ?: return@launch
            val burned = AnnotationEngine.burnAnnotationsIntoBitmap(bmp, paths, redactions, signatures, placedTexts, brightness, contrast)
            val newPath = ImageProcessor.saveBitmapToFile(context, burned, "ann_")

            // Free memory
            if (bmp != burned) bmp.recycle()
            burned.recycle()

            qualityCache.remove(page.id)
            val updated = page.copy(processedImagePath = newPath)
            repository.updatePage(updated)
        }
    }

    /**
     * Exports current active document to PDF
     */
    fun exportDocumentToPdf(config: PdfExportConfig, selectedPageIds: Set<Long>? = null, onComplete: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            val pages = if (selectedPageIds != null && selectedPageIds.isNotEmpty()) {
                _uiState.value.activePages.filter { selectedPageIds.contains(it.id) }
            } else {
                _uiState.value.activePages
            }
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

    
    fun saveDocumentToGallery(context: Context, docId: Long) {
        viewModelScope.launch {
            try {
                val pages = repository.getPagesList(docId)
                if (pages.isEmpty()) return@launch
                
                var successCount = 0
                pages.forEach { page ->
                    val file = java.io.File(page.processedImagePath)
                    if (file.exists()) {
                        val success = ImageProcessor.saveToGallery(context, file)
                        if (success) successCount++
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (successCount > 0) {
                        Toast.makeText(context, context.getString(R.string.txt_saved_to_gallery), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.txt_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun saveActivePageToGallery(context: Context) {
        viewModelScope.launch {
            try {
                val activePage = _uiState.value.activePages.getOrNull(_uiState.value.selectedPageIndex)
                if (activePage != null) {
                    val file = java.io.File(activePage.processedImagePath)
                    if (file.exists()) {
                        val success = ImageProcessor.saveToGallery(context, file)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, context.getString(R.string.txt_saved_to_gallery), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.txt_save_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    
    fun movePageLeft(pageId: Long) {
        val docId = _uiState.value.activeDocument?.id ?: return
        val pages = _uiState.value.activePages.toMutableList()
        val index = pages.indexOfFirst { it.id == pageId }
        if (index > 0) {
            java.util.Collections.swap(pages, index, index - 1)
            viewModelScope.launch {
                repository.reorderPages(docId, pages)
            }
        }
    }
    
    
    fun reorderPages(newOrder: List<com.example.data.model.PageEntity>) {
        val docId = _uiState.value.activeDocument?.id ?: return
        viewModelScope.launch {
            repository.reorderPages(docId, newOrder)
        }
    }

    fun movePageRight(pageId: Long) {
        val docId = _uiState.value.activeDocument?.id ?: return
        val pages = _uiState.value.activePages.toMutableList()
        val index = pages.indexOfFirst { it.id == pageId }
        if (index >= 0 && index < pages.size - 1) {
            java.util.Collections.swap(pages, index, index + 1)
            viewModelScope.launch {
                repository.reorderPages(docId, pages)
            }
        }
    }

    fun setPin(pin: String) {
        prefs.userPin = pin
        _uiState.update { it.copy(userPin = pin, hasPinConfigured = pin.length == 4) }
    }

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDefaultPdfPageSize(size: com.example.data.model.PageSizePreset) {
        prefs.pdfPageSize = size
        _uiState.update { it.copy(defaultPdfPageSize = size) }
    }

    fun setDefaultPdfCompression(comp: com.example.data.model.CompressionPreset) {
        prefs.pdfCompression = comp
        _uiState.update { it.copy(defaultPdfCompression = comp) }
    }

    
    private val backupManager = BackupManager(context, repository)
    
    private val _backupEvent = MutableSharedFlow<String>()
    val backupEvent = _backupEvent.asSharedFlow()

    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.createBackup(uri)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _backupEvent.emit("Backup created successfully")
            } else {
                _backupEvent.emit("Backup failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.restoreBackup(uri)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _backupEvent.emit("Restore completed successfully. Restored ${result.getOrNull()} documents.")
                setupDocumentStream()
            } else {
                _backupEvent.emit("Restore failed: ${result.exceptionOrNull()?.message}")
            }
        }
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
