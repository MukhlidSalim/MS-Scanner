package com.example.ui.viewmodel
import android.content.Context
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentEntity
import com.example.data.model.LockType
import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.data.repository.DocumentSaveRequest
import com.example.data.repository.StorageStats
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.TessDataManager
import com.example.util.SearchText
import com.example.ui.util.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
enum class ExportPdfAction {
    SHARE,
    SAVE_TO_DOWNLOADS,
    SAVE_AS,
    OPEN,
    PREVIEW
}
enum class SortOrder {
    DATE_CREATED,
    DATE_MODIFIED,
    NAME,
    SIZE
}
data class DocumentListUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val favoriteDocuments: List<DocumentEntity> = emptyList(),
    val trashDocuments: List<DocumentEntity> = emptyList(),
    val folders: List<String> = emptyList(),
    val selectedFolder: String = "ALL",
    val selectedCategory: DocumentCategory = DocumentCategory.ALL,
    val searchQuery: String = "",
    val sortMode: SortOrder = SortOrder.DATE_MODIFIED,
    val showFavoritesOnly: Boolean = false,
    val storageStats: StorageStats? = null,
    val isAppLocked: Boolean = false,
    /** Never contains the PIN (it is stored hashed). Kept for source compatibility. */
    val userPin: String = "",
    val hasPinConfigured: Boolean = false,
    val themeMode: String = "System",
    val defaultPdfPageSize: com.example.data.model.PageSizePreset = com.example.data.model.PageSizePreset.A4,
    val defaultPdfCompression: com.example.data.model.CompressionPreset = com.example.data.model.CompressionPreset.HIGH,
    val lockType: com.example.data.model.LockType = com.example.data.model.LockType.NONE,
    val ocrLanguage: com.example.engine.ocr.OcrLanguage = com.example.engine.ocr.OcrLanguage.AUTO,
    val isLoading: Boolean = false,
    /** Arabic OCR model present on the device (bundled or downloaded once). */
    val arabicModelReady: Boolean = false,
    val isDownloadingArabicModel: Boolean = false
)
class DocumentListViewModel(
    context: Context,
    private val repository: DocumentRepository
) : ViewModel() {
    private val context: Context = context.applicationContext
    private val prefs = AppPreferences(this.context)
    private val _uiState = MutableStateFlow(DocumentListUiState(
        userPin = "",
        hasPinConfigured = prefs.hasPin,
        themeMode = prefs.themeMode,
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression,
        lockType = prefs.lockType,
        // A stored value that no longer exists must not crash the app at startup.
        ocrLanguage = runCatching { com.example.engine.ocr.OcrLanguage.valueOf(prefs.ocrLanguage) }
            .getOrDefault(com.example.engine.ocr.OcrLanguage.AUTO)
    ))
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()
    // Buffered: sending an event can never suspend (block) a delete / restore / export coroutine.
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val backupManager = com.example.data.repository.BackupManager(this.context, repository)
    private data class FilterState(
        val folder: String = "ALL",
        val category: DocumentCategory = DocumentCategory.ALL,
        val query: String = "",
        val sort: SortOrder = SortOrder.DATE_MODIFIED,
        val showFavorites: Boolean = false
    )
    private val filterTrigger = MutableStateFlow(FilterState())
    /** Normalized search text per document, recomputed only when the document changes (id -> updatedAt, text). */
    private val searchIndex = HashMap<Long, Pair<Long, String>>()
    init {
        _uiState.update { it.copy(arabicModelReady = TessDataManager.isArabicReady(this.context)) }
        setupDocumentStream()
        loadFolders()
        refreshStorageStats()
    }
    private fun searchableText(doc: DocumentEntity): String {
        val cached = searchIndex[doc.id]
        if (cached != null && cached.first == doc.updatedAt) return cached.second
        val text = SearchText.normalize(listOf(doc.title, doc.suggestedTitle, doc.tagsCsv, doc.ocrText).joinToString(" "))
        searchIndex[doc.id] = doc.updatedAt to text
        return text
    }

    private fun setupDocumentStream() {
        viewModelScope.launch {
            combine(
                repository.getAllDocuments(),
                filterTrigger
            ) { docs, filter ->
                var filtered = docs
                if (filter.folder != "ALL") {
                    filtered = filtered.filter { it.folderName == filter.folder }
                }
                // Previously the category chip was stored but never applied.
                if (filter.category != DocumentCategory.ALL) {
                    filtered = filtered.filter { it.category == filter.category.name }
                }
                if (filter.showFavorites) {
                    filtered = filtered.filter { it.isFavorite }
                }
                if (filter.query.isNotBlank()) {
                    // Title + suggested title + tags + recognized TEXT of every page, Arabic-normalized.
                    filtered = filtered.filter { SearchText.matches(searchableText(it), filter.query) }
                }
                applySorting(filtered, filter.sort)
            }.flowOn(Dispatchers.Default) // filtering / normalization never on the main thread
                .collectLatest { sortedAndFiltered ->
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
            repository.observeFolders().collectLatest { foldersList ->
                _uiState.update { it.copy(folders = foldersList) }
            }
        }
    }
    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = repository.getStorageStats()
            _uiState.update { it.copy(storageStats = stats) }
        }
    }
    fun filterByFolder(folder: String) {
        _uiState.update { it.copy(selectedFolder = folder) }
        filterTrigger.value = filterTrigger.value.copy(folder = folder)
    }
    fun filterByCategory(cat: DocumentCategory) {
        _uiState.update { it.copy(selectedCategory = cat) }
        filterTrigger.value = filterTrigger.value.copy(category = cat)
    }
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        filterTrigger.value = filterTrigger.value.copy(query = query)
    }
    fun setSortMode(mode: SortOrder) {
        _uiState.update { it.copy(sortMode = mode) }
        filterTrigger.value = filterTrigger.value.copy(sort = mode)
    }
    val showFavoritesOnly: StateFlow<Boolean> = _uiState.map { it.showFavoritesOnly }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val filteredDocuments: StateFlow<List<DocumentEntity>> = _uiState.map { it.documents }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setShowFavoritesOnly(show: Boolean) {
        _uiState.update { it.copy(showFavoritesOnly = show) }
        filterTrigger.value = filterTrigger.value.copy(showFavorites = show)
    }
    fun toggleFavoritesFilter() {
        val newValue = !_uiState.value.showFavoritesOnly
        setShowFavoritesOnly(newValue)
    }
    private fun applySorting(docs: List<DocumentEntity>, mode: SortOrder): List<DocumentEntity> {
        return docs.sortedWith(
            when (mode) {
                SortOrder.DATE_CREATED -> compareByDescending { it.createdAt }
                SortOrder.DATE_MODIFIED -> compareByDescending { it.updatedAt }
                SortOrder.NAME -> compareBy { it.title.lowercase() }
                SortOrder.SIZE -> compareByDescending { it.sizeBytes }
            }
        )
    }
    fun toggleFavorite(docId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(docId)
        }
    }
    private val isArabic: Boolean get() = context.resources.configuration.locales[0].language == "ar"

    /**
     * Delete = move to Trash + "Undo" snackbar. Nothing is ever deleted permanently from here
     * (the previous version permanently deleted the files 5 seconds later: data loss if Undo was missed).
     */
    fun deleteDocument(docId: Long) = moveToTrashWithUndo(listOf(docId))

    fun moveToTrashWithUndo(docIds: List<Long>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            docIds.forEach {
                com.example.engine.ocr.DocumentAnalysisWorker.cancel(context, it)
                repository.moveToTrash(it)
            }
            refreshStorageStats()
            _events.send(UiEvent.ShowSnackbarWithAction(
                message = if (isArabic) "تم نقل ${docIds.size} مستند إلى سلة المحذوفات" else "${docIds.size} document(s) moved to Trash",
                actionLabel = if (isArabic) "تراجع" else "Undo",
                action = { undoDelete(docIds) }
            ))
        }
    }

    private fun undoDelete(docIds: List<Long>) {
        viewModelScope.launch {
            docIds.forEach { repository.restoreFromTrash(it) }
            refreshStorageStats()
        }
    }

    /** Favorite for several documents: if any is not favorite, all become favorite; otherwise all are removed. */
    fun setFavorite(docIds: List<Long>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            val docs = docIds.mapNotNull { repository.getDocumentById(it) }
            val makeFavorite = docs.any { !it.isFavorite }
            docs.forEach { if (it.isFavorite != makeFavorite) repository.updateDocument(it.copy(isFavorite = makeFavorite)) }
        }
    }

    fun moveToTrash(docId: Long) {
        viewModelScope.launch {
            com.example.engine.ocr.DocumentAnalysisWorker.cancel(context, docId)
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
    /** Several documents get numbered titles ("Title 1", "Title 2"…) instead of identical names. */
    fun renameDocuments(docIds: List<Long>, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isBlank() || docIds.isEmpty()) return
        viewModelScope.launch {
            docIds.forEachIndexed { i, id ->
                val doc = repository.getDocumentById(id) ?: return@forEachIndexed
                repository.updateDocument(doc.copy(title = if (docIds.size == 1) clean else "$clean ${i + 1}"))
            }
        }
    }
    fun changeDocumentFolder(docId: Long, folder: String) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            repository.updateDocument(doc.copy(folderName = folder))
        }
    }
    /** Prints the WHOLE document (previously only the first page was printed). [context] must be an Activity. */
    fun printDocumentById(context: Context, docId: Long) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            val paths = repository.getPagesList(docId).map { p ->
                p.processedImagePath.takeIf { it.isNotBlank() && File(it).exists() } ?: p.rawImagePath
            }
            if (paths.isNotEmpty()) com.example.engine.pdf.PdfEngine.printScannedDocuments(context, doc.title, paths)
        }
    }
    suspend fun getSelectedDocumentsWithPages(docIds: List<Long>): List<Pair<DocumentEntity, List<com.example.data.model.PageEntity>>> = withContext(Dispatchers.IO) {
        docIds.mapNotNull { id ->
            val doc = repository.getDocumentById(id) ?: return@mapNotNull null
            val pages = repository.getPagesForDocumentSync(id)
            Pair(doc, pages)
        }
    }
    fun exportDocumentsAsPdf(
        context: Context,
        docIds: List<Long>,
        config: com.example.engine.pdf.PdfExportConfig,
        action: ExportPdfAction,
        targetSaveUri: android.net.Uri? = null,
        onSuccess: (File, android.net.Uri?, String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        if (docIds.isEmpty() || _uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val allPages = mutableListOf<com.example.data.model.PageEntity>()
                for (id in docIds) {
                    allPages.addAll(repository.getPagesForDocumentSync(id))
                }
                if (allPages.isEmpty()) {
                    val msg = "No pages found in selected documents"
                    _events.send(UiEvent.Error(msg))
                    onError(msg)
                    return@launch
                }
                val pairs = allPages.map { page ->
                    val path = if (page.processedImagePath.isNotBlank() && File(page.processedImagePath).exists()) {
                        page.processedImagePath
                    } else {
                        page.rawImagePath
                    }
                    Pair(path, page.ocrText)
                }
                val pdfFile = com.example.engine.pdf.PdfEngine.generatePdf(context, pairs, config)
                when (action) {
                    ExportPdfAction.SHARE -> {
                        com.example.engine.pdf.PdfEngine.sharePdf(context, pdfFile)
                        val uri = com.example.engine.pdf.PdfEngine.getFileProviderUri(context, pdfFile)
                        onSuccess(pdfFile, uri, "Shared")
                    }
                    ExportPdfAction.SAVE_TO_DOWNLOADS -> {
                        // Visible to the user (Downloads/MS Scanner); legacy app-private folder only as fallback.
                        val publicUri = com.example.engine.pdf.PdfEngine.savePdfToDownloads(context, pdfFile, config.title)
                        if (publicUri != null) {
                            onSuccess(pdfFile, publicUri, "Downloads/MS Scanner")
                        } else {
                            val (savedUri, path) = withContext(Dispatchers.IO) {
                                com.example.engine.pdf.PdfEngine.savePdfToStorage(context, pdfFile, config.title)
                            }
                            if (savedUri != null) onSuccess(pdfFile, savedUri, path ?: pdfFile.name)
                            else onError("Failed to save PDF")
                        }
                    }
                    ExportPdfAction.SAVE_AS -> {
                        if (targetSaveUri != null) {
                            val ok = withContext(Dispatchers.IO) {
                                com.example.engine.pdf.PdfEngine.copyPdfToUri(context, pdfFile, targetSaveUri)
                            }
                            if (ok) onSuccess(pdfFile, targetSaveUri, "Selected Folder")
                            else onError("Failed to save to selected location")
                        } else {
                            val uri = com.example.engine.pdf.PdfEngine.getFileProviderUri(context, pdfFile)
                            onSuccess(pdfFile, uri, pdfFile.name)
                        }
                    }
                    ExportPdfAction.OPEN -> {
                        com.example.engine.pdf.PdfEngine.openPdf(context, pdfFile)
                        val uri = com.example.engine.pdf.PdfEngine.getFileProviderUri(context, pdfFile)
                        onSuccess(pdfFile, uri, "Opened")
                    }
                    ExportPdfAction.PREVIEW -> {
                        val uri = com.example.engine.pdf.PdfEngine.getFileProviderUri(context, pdfFile)
                        onSuccess(pdfFile, uri, "Preview")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = e.localizedMessage ?: "PDF export failed"
                _events.send(UiEvent.Error(errorMsg))
                onError(errorMsg)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
    fun shareDocumentsAsPdf(context: Context, docIds: List<Long>) {
        if (docIds.isEmpty()) return
        val config = com.example.engine.pdf.PdfExportConfig(
            title = "Shared_Documents",
            pageSize = _uiState.value.defaultPdfPageSize,
            compression = _uiState.value.defaultPdfCompression
        )
        exportDocumentsAsPdf(
            context = context,
            docIds = docIds,
            config = config,
            action = ExportPdfAction.SHARE
        )
    }
    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
            refreshStorageStats()
        }
    }
    /** Creates an (empty) folder and opens it, so the next scan is saved there. */
    fun createFolder(name: String) {
        viewModelScope.launch {
            val clean = name.trim()
            if (repository.createFolder(clean)) {
                filterByFolder(clean)
                _events.send(UiEvent.ShowToast("Folder created: $clean"))
            } else {
                _events.send(UiEvent.Error("Invalid or existing folder name"))
            }
        }
    }

    fun moveDocumentsToFolder(docIds: List<Long>, folder: String) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            val target = folder.trim()
            if (target.isNotBlank() && !repository.isReservedFolder(target) &&
                _uiState.value.folders.none { it.equals(target, ignoreCase = true) }
            ) {
                repository.createFolder(target)
            }
            repository.moveDocumentsToFolder(docIds, target.ifBlank { "Default" })
            _events.send(UiEvent.ShowToast("Moved ${docIds.size} document(s)"))
        }
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
    /**
     * Merges documents into a NEW document with its OWN copies of the page files (the previous code
     * shared the same files, so deleting one document broke the other). Crop, rotation and filter are
     * kept; the merge is written through the single save transaction.
     */
    fun mergeDocuments(docIds: List<Long>) {
        if (docIds.size < 2) return
        viewModelScope.launch {
            val created = mutableListOf<File>()
            try {
                val docs = docIds.mapNotNull { id -> _uiState.value.documents.find { it.id == id } }
                if (docs.size < 2) return@launch
                val scansDir = File(context.filesDir, "scans").apply { mkdirs() }
                val newPages = withContext(Dispatchers.IO) {
                    docs.flatMap { repository.getPagesForDocumentSync(it.id).sortedBy { p -> p.pageIndex } }
                        .mapIndexed { index, page ->
                            fun copy(path: String): String {
                                val src = File(path)
                                if (path.isBlank() || !src.exists()) return path
                                val dst = File(scansDir, "merge_${System.nanoTime()}_${src.name}")
                                src.copyTo(dst, overwrite = true)
                                created += dst
                                return dst.absolutePath
                            }
                            val raw = copy(page.rawImagePath)
                            QuadStore.load(page.rawImagePath)?.let { QuadStore.save(raw, it) }
                            val proc = if (page.processedImagePath == page.rawImagePath) raw else copy(page.processedImagePath)
                            page.copy(id = 0L, documentId = 0L, pageIndex = index, rawImagePath = raw, processedImagePath = proc)
                        }
                }
                repository.saveDocument(
                    DocumentSaveRequest(
                        existingDocId = null,
                        title = docs.first().title + "_Merged",
                        folderName = _uiState.value.selectedFolder.takeIf { it != "ALL" } ?: "Default",
                        category = docs.first().category,
                        pages = newPages
                    )
                )
                refreshStorageStats()
            } catch (e: CancellationException) {
                created.forEach { it.delete() }
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                created.forEach { it.delete() }
                _events.send(UiEvent.Error("Merge failed: ${e.localizedMessage ?: "storage error"}"))
            }
        }
    }
    fun lockApp() {
        if (prefs.hasPin) {
            _uiState.update { it.copy(isAppLocked = true) }
        }
    }
    /** Hashed comparison with rate limiting (see AppPreferences.verifyPin). */
    fun verifyPin(entered: String): Boolean {
        val ok = prefs.verifyPin(entered)
        if (ok) {
            _uiState.update { it.copy(isAppLocked = false) }
        }
        return ok
    }
    /** Seconds before the next PIN attempt is allowed (0 = now). */
    fun pinLockoutSeconds(): Int = ((prefs.pinLockoutRemainingMs() + 999) / 1000).toInt()
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
    fun createBackup(uri: android.net.Uri) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.createBackup(uri)
            val msg = if (result.isSuccess) "Backup created: ${result.getOrNull()} documents" else "Backup failed: ${result.exceptionOrNull()?.message}"
            _uiState.update { it.copy(isLoading = false) }
            _events.send(UiEvent.ShowSnackbar(msg))
        }
    }
    fun restoreBackup(uri: android.net.Uri) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.restoreBackup(uri)
            val msg = if (result.isSuccess) "Restored ${result.getOrNull()} documents" else "Restore failed: ${result.exceptionOrNull()?.message}"
            _uiState.update { it.copy(isLoading = false) }
            _events.send(UiEvent.ShowSnackbar(msg))
            // Room Flows update the lists automatically (re-subscribing here leaked a collector per restore).
            refreshStorageStats()
        }
    }
    /** Saving a PIN also activates the PIN lock (previously the PIN was stored but the lock stayed off). */
    fun setPin(pin: String) {
        prefs.setPin(pin)
        prefs.lockType = LockType.PIN
        _uiState.update { it.copy(userPin = "", hasPinConfigured = prefs.hasPin, lockType = LockType.PIN) }
    }
    fun setLockType(type: com.example.data.model.LockType) {
        // A PIN lock without a PIN could never be unlocked: require the PIN dialog first.
        if (type == LockType.PIN && !prefs.hasPin) {
            _events.trySend(UiEvent.Error("Set a PIN first"))
            return
        }
        prefs.lockType = type
        _uiState.update { it.copy(lockType = type) }
    }
    fun setOcrLanguage(lang: com.example.engine.ocr.OcrLanguage) {
        prefs.ocrLanguage = lang.name
        _uiState.update { it.copy(ocrLanguage = lang) }
        if (lang != com.example.engine.ocr.OcrLanguage.ENGLISH && !_uiState.value.arabicModelReady) downloadArabicModel()
    }

    /** One-time provisioning of the Arabic OCR model (bundled asset or ~1.4 MB download). */
    fun downloadArabicModel() {
        if (_uiState.value.isDownloadingArabicModel) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingArabicModel = true) }
            val ok = TessDataManager.ensureArabic(context)
            _uiState.update { it.copy(isDownloadingArabicModel = false, arabicModelReady = ok) }
            _events.send(
                if (ok) UiEvent.ShowToast("Arabic text recognition is ready")
                else UiEvent.Error("Could not download the Arabic model. Check the internet connection and retry.")
            )
        }
    }
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refreshStorageStats()
        }
    }
}
