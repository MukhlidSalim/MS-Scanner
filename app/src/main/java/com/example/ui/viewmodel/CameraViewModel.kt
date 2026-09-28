package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.example.data.model.DocumentCategory
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.BatchProcessingQueue
import com.example.engine.cv.BatchQueueState
import com.example.engine.cv.ImageProcessingWorker
import com.example.engine.cv.ImageProcessor
import com.example.engine.ocr.DocumentAiEngine
import com.example.ui.util.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CameraUiState(
    val pendingPages: List<Pair<String, String>> = emptyList(),
    val pagesPendingEdit: List<Pair<String, String>> = emptyList(),
    val importedUrisPending: List<Uri> = emptyList(),
    val isLoading: Boolean = false,
    val detectedCategory: DocumentCategory = DocumentCategory.OTHER,
    val detectedOcrText: String = "",
    val batchQueueState: BatchQueueState = BatchQueueState()
)

class CameraViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>()
    val events = _events.receiveAsFlow()

    private val workManager = WorkManager.getInstance(context)
    val batchProcessingQueue = BatchProcessingQueue(viewModelScope)

    init {
        // Collect batch queue state continuously to drive non-blocking UI updates
        viewModelScope.launch {
            batchProcessingQueue.queueState.collectLatest { queueState ->
                _uiState.update { it.copy(batchQueueState = queueState) }
            }
        }
    }

    fun setPagesPendingEdit(pages: List<Pair<String, String>>) {
        _uiState.update { it.copy(pagesPendingEdit = pages) }
    }

    fun setImportedUrisPendingEdit(uris: List<Uri>, autoCrop: Boolean = true) {
        _uiState.update { 
            it.copy(
                importedUrisPending = uris,
                pagesPendingEdit = emptyList(),
                isLoading = false // Non-blocking: UI remains interactive immediately
            ) 
        }
        processImportedUrisWithBatchQueue(uris, autoCrop)
    }

    private fun processImportedUrisWithBatchQueue(uris: List<Uri>, autoCrop: Boolean) {
        val completedPagesMap = mutableMapOf<Int, Pair<String, String>>()

        batchProcessingQueue.enqueueUris(
            context = context,
            uris = uris,
            autoCrop = autoCrop,
            onPageReady = { index, rawPath, procPath, _ ->
                completedPagesMap[index] = Pair(rawPath, procPath)
                // Build contiguous ordered list of completed pages so user can view/swipe immediately
                val orderedList = (0..completedPagesMap.keys.maxOrNull()!!)
                    .mapNotNull { completedPagesMap[it] }
                _uiState.update { it.copy(pagesPendingEdit = orderedList) }

                // Auto-analyze OCR & category on the very first page as soon as it arrives
                if (index == 0) {
                    analyzePendingFirstPage()
                }
            },
            onAllComplete = {
                // Ensure final pages list is updated
                val finalPages = (0 until uris.size).mapNotNull { completedPagesMap[it] }
                if (finalPages.isNotEmpty()) {
                    _uiState.update { it.copy(pagesPendingEdit = finalPages) }
                }
            }
        )
    }

    fun autoCropAllPendingPages() {
        val currentPages = _uiState.value.pagesPendingEdit
        if (currentPages.isEmpty()) return

        batchProcessingQueue.enqueueExistingPagePairs(
            context = context,
            pages = currentPages,
            onPageUpdated = { index, newProcPath, _ ->
                updatePendingPageProcessedImage(index, newProcPath)
            }
        )
    }

    fun pauseBatchQueue() = batchProcessingQueue.pause()
    fun resumeBatchQueue() = batchProcessingQueue.resume()
    fun skipRemainingAutoCrop() = batchProcessingQueue.skipRemaining()
    fun cancelBatchQueue() = batchProcessingQueue.cancel()

    fun rotatePendingPage(index: Int, clockwise: Boolean) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index !in pages.indices) return
        val pagePair = pages[index]
        
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_ROTATE_PENDING,
                ImageProcessingWorker.KEY_RAW_PATH to pagePair.first,
                ImageProcessingWorker.KEY_PROC_PATH to pagePair.second,
                ImageProcessingWorker.KEY_CLOCKWISE to clockwise
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        workManager.enqueueUniqueWork("rotate_pending_$index", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { workInfo ->
                if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    val newPath = workInfo.outputData.getString(ImageProcessingWorker.KEY_RESULT_PATH) ?: pagePair.second
                    val newPages = _uiState.value.pagesPendingEdit.toMutableList()
                    newPages[index] = Pair(pagePair.first, newPath)
                    _uiState.update { it.copy(pagesPendingEdit = newPages, isLoading = false) }
                } else if (workInfo?.state == WorkInfo.State.FAILED) {
                    _uiState.update { it.copy(isLoading = false) }
                    viewModelScope.launch { _events.send(UiEvent.Error("Failed to rotate pending page")) }
                }
            }
        }
    }

    fun analyzePendingFirstPage() {
        val pages = _uiState.value.pagesPendingEdit
        if (pages.isEmpty()) return
        
        viewModelScope.launch {
            try {
                val firstPage = pages.first()
                val bmp = ImageProcessor.loadBitmapFromFile(firstPage.second, 1200)
                if (bmp != null) {
                    val result = DocumentAiEngine.performOfflineOcr(bmp)
                    _uiState.update { 
                        it.copy(
                            detectedCategory = result.detectedCategory,
                            detectedOcrText = result.fullText
                        )
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importPagesAsDocument(title: String = "", selectedFolder: String = "Default", onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.pagesPendingEdit
            if (pages.isEmpty()) return@launch
            
            val docId = repository.createDocumentWithPages(
                title = title,
                folderName = selectedFolder,
                category = _uiState.value.detectedCategory.name,
                ocrText = _uiState.value.detectedOcrText,
                pages = pages
            )
            // Reset detected data for next use
            _uiState.update { it.copy(detectedCategory = DocumentCategory.OTHER, detectedOcrText = "", pagesPendingEdit = emptyList()) }
            onComplete(docId)
        }
    }

    fun updatePendingPageProcessedImage(index: Int, newPath: String) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index in pages.indices) {
            val old = pages[index]
            pages[index] = Pair(old.first, newPath)
            _uiState.update { it.copy(pagesPendingEdit = pages) }
        }
    }

    fun commitPendingPagesToDocument(docId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.pagesPendingEdit
            if (pages.isEmpty()) return@launch
            
            val doc = repository.getDocumentById(docId) ?: return@launch
            var pageCount = doc.pageCount
            for (pagePair in pages) {
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
            
            clearPendingPages()
            onComplete()
        }
    }

    fun clearPendingPages(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            val state = _uiState.value
            state.pendingPages.forEach { 
                java.io.File(it.first).delete()
                java.io.File(it.second).delete()
            }
            state.pagesPendingEdit.forEach { 
                java.io.File(it.first).delete()
                java.io.File(it.second).delete()
            }
        }
        _uiState.update { it.copy(pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList()) }
    }
}
