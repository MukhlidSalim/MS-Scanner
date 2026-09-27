package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.model.DocumentCategory
import com.example.data.model.DocumentEntity

import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.data.repository.StorageStats
import com.example.engine.updater.*
import com.example.ui.util.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortOrder {
    DATE_CREATED,
    DATE_MODIFIED,
    NAME,
    SIZE
}
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

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
    val userPin: String = "",
    val hasPinConfigured: Boolean = false,
    val themeMode: String = "System",
    val defaultPdfPageSize: com.example.data.model.PageSizePreset = com.example.data.model.PageSizePreset.A4,
    val defaultPdfCompression: com.example.data.model.CompressionPreset = com.example.data.model.CompressionPreset.HIGH,
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = false
)

class DocumentListViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val prefs = AppPreferences(context)
    private val _uiState = MutableStateFlow(DocumentListUiState(
        userPin = prefs.userPin,
        hasPinConfigured = prefs.userPin.length == 4,
        themeMode = prefs.themeMode,
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression,
        biometricEnabled = prefs.biometricEnabled
    ))
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>()
    val events = _events.receiveAsFlow()

    private val backupManager = com.example.data.repository.BackupManager(context, repository)

    private data class FilterState(
        val folder: String = "ALL",
        val category: DocumentCategory = DocumentCategory.ALL,
        val query: String = "",
        val sort: SortOrder = SortOrder.DATE_MODIFIED,
        val showFavorites: Boolean = false
    )
    private val filterTrigger = MutableStateFlow(FilterState())

    private val updateManager = GitHubUpdateManager(context)
    val updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)

    init {
        setupDocumentStream()
        loadFolders()
        refreshStorageStats()
        checkUpdatesOnLaunch()
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
                if (filter.showFavorites) {
                    filtered = filtered.filter { it.isFavorite }
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

    
    fun deleteDocument(docId: Long) {
        viewModelScope.launch {
            // Soft delete
            repository.moveToTrash(docId)
            refreshStorageStats()
            
            // Show Snackbar
            _events.send(UiEvent.ShowSnackbarWithAction(
                message = "تم حذف المستند",
                actionLabel = "تراجع",
                action = { undoDelete(docId) }
            ))
            
            // Wait 5 seconds
            kotlinx.coroutines.delay(5000)
            
            // Check if it's still in trash (not undone)
            val doc = repository.getDocumentById(docId)
            if (doc != null && doc.isTrash) {
                repository.deleteDocumentPermanently(docId)
                refreshStorageStats()
            }
        }
    }
    
    private fun undoDelete(docId: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(docId)
            refreshStorageStats()
            _events.send(UiEvent.ShowToast("تم التراجع عن الحذف"))
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

    fun renameDocuments(docIds: List<Long>, newTitle: String) {
        viewModelScope.launch {
            for (id in docIds) {
                val doc = repository.getDocumentById(id) ?: continue
                repository.updateDocument(doc.copy(title = newTitle))
            }
        }
    }

    fun changeDocumentFolder(docId: Long, folder: String) {
        viewModelScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            repository.updateDocument(doc.copy(folderName = folder))
        }
    }

    fun printDocumentById(context: Context, docId: Long) {
        viewModelScope.launch {
            val pages = repository.getPagesList(docId)
            if (pages.isNotEmpty()) {
                val firstPage = pages.first()
                val bmp = com.example.engine.cv.ImageProcessor.loadBitmapFromFile(firstPage.processedImagePath) ?: return@launch
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        val printHelper = androidx.print.PrintHelper(context).apply {
                            scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
                        }
                        printHelper.printBitmap("Document_${docId}", bmp)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun shareDocumentsAsPdf(context: Context, docIds: List<Long>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val allPages = mutableListOf<com.example.data.model.PageEntity>()
            for (id in docIds) {
                allPages.addAll(repository.getPagesForDocumentSync(id))
            }
            if (allPages.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            
            val config = com.example.engine.pdf.PdfExportConfig(
                title = "Shared_Documents",
                pageSize = com.example.data.model.PageSizePreset.A4,
                compression = com.example.data.model.CompressionPreset.HIGH
            )
            val pairs = allPages.map { Pair(it.rawImagePath, it.processedImagePath) }
            val pdfFile = com.example.engine.pdf.PdfEngine.generatePdf(context, pairs, config)
            
            _uiState.update { it.copy(isLoading = false) }
            
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

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
            refreshStorageStats()
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
            repository.createDocumentWithPages(
                title = newTitle,
                folderName = _uiState.value.selectedFolder.takeIf { it != "ALL" } ?: "Default",
                category = "OTHER",
                ocrText = "",
                pages = allPages.map { Pair(it.rawImagePath, it.processedImagePath) }
            )
            refreshStorageStats()
        }
    }

    fun checkUpdates(isManual: Boolean = false) {
        viewModelScope.launch {
            updateCheckState.value = UpdateCheckState.Checking
            try {
                val info = updateManager.checkUpdate(prefs.githubRepoSlug)
                if (info.isUpdateAvailable) {
                    if (isManual || prefs.ignoredUpdateVersion != info.latestVersion) {
                        updateCheckState.value = UpdateCheckState.Available(info, isManual)
                    } else {
                        updateCheckState.value = UpdateCheckState.Idle
                    }
                } else {
                    updateCheckState.value = UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME, isManual)
                }
                prefs.lastUpdateCheckTime = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
                updateCheckState.value = UpdateCheckState.Error(
                    messageAr = "تعذر التحقق من التحديثات: ${e.localizedMessage ?: "خطأ في الاتصال بالإنترنت"}",
                    messageEn = "Failed to check updates: ${e.localizedMessage ?: "Network error"}",
                    isManual = isManual
                )
            }
        }
    }

    fun dismissUpdate(ignoreVersion: Boolean = false, version: String = "") {
        if (ignoreVersion && version.isNotBlank()) {
            prefs.ignoredUpdateVersion = version
        }
        updateCheckState.value = UpdateCheckState.Idle
        updateDownloadState.value = UpdateDownloadState.Idle
    }

    fun downloadAndInstallUpdate(updateInfo: AppUpdateInfo) {
        viewModelScope.launch {
            if (updateInfo.downloadUrl.isBlank()) {
                updateDownloadState.value = UpdateDownloadState.Error(
                    messageAr = "رابط ملف التحديث (APK) غير متوفر حالياً على جيت هب",
                    messageEn = "Update APK URL is currently not available on GitHub"
                )
                return@launch
            }

            try {
                updateDownloadState.value = UpdateDownloadState.Downloading(0L, updateInfo.apkSize, 0f)
                val apkFile = updateManager.downloadApk(
                    downloadUrl = updateInfo.downloadUrl,
                    targetVersion = updateInfo.latestVersion
                ) { bytesRead, totalBytes, progress ->
                    updateDownloadState.value = UpdateDownloadState.Downloading(bytesRead, totalBytes, progress)
                }

                if (updateManager.canInstallPackages()) {
                    updateDownloadState.value = UpdateDownloadState.ReadyToInstall(apkFile, updateInfo)
                    updateManager.launchInstallApk(apkFile)
                } else {
                    updateDownloadState.value = UpdateDownloadState.PermissionRequired(apkFile, updateInfo)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateDownloadState.value = UpdateDownloadState.Error(
                    messageAr = "فشل تحميل التحديث: ${e.localizedMessage ?: "يرجى التحقق من الاتصال بالإنترنت"}",
                    messageEn = "Update download failed: ${e.localizedMessage ?: "Please check internet connection"}"
                )
            }
        }
    }

    fun requestInstallApk(apkFile: File) {
        if (updateManager.canInstallPackages()) {
            updateManager.launchInstallApk(apkFile)
        } else {
            context.startActivity(updateManager.getInstallPermissionIntent())
        }
    }

    fun checkUpdatesOnLaunch() {
        if (prefs.autoCheckUpdates) {
            val lastCheck = prefs.lastUpdateCheckTime
            val now = System.currentTimeMillis()
            if (now - lastCheck > 1000 * 60 * 30) {
                checkUpdates(isManual = false)
            }
        }
    }

    fun lockApp() {
        if (prefs.userPin.length == 4) {
            _uiState.update { it.copy(isAppLocked = true) }
        }
    }

    fun verifyPin(entered: String): Boolean {
        val ok = entered == prefs.userPin
        if (ok) {
            _uiState.update { it.copy(isAppLocked = false) }
        }
        return ok
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

    fun createBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.createBackup(uri)
            val msg = if (result.isSuccess) "Backup created: ${result.getOrNull()} documents" else "Backup failed: ${result.exceptionOrNull()?.message}"
            _uiState.update { it.copy(isLoading = false) }
            viewModelScope.launch { _events.send(UiEvent.ShowSnackbar(msg)) }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.restoreBackup(uri)
            val msg = if (result.isSuccess) "Restored ${result.getOrNull()} documents" else "Restore failed: ${result.exceptionOrNull()?.message}"
            _uiState.update { it.copy(isLoading = false) }
            viewModelScope.launch { _events.send(UiEvent.ShowSnackbar(msg)) }
            setupDocumentStream()
            loadFolders()
            refreshStorageStats()
        }
    }

    fun setPin(pin: String) {
        prefs.userPin = pin
        _uiState.update { it.copy(userPin = pin, hasPinConfigured = pin.length == 4) }
    }

    fun toggleBiometric(enabled: Boolean) {
        prefs.biometricEnabled = enabled
        _uiState.update { it.copy(biometricEnabled = enabled) }
    }

    fun clearCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            context.cacheDir.deleteRecursively()
            refreshStorageStats()
        }
    }
}
