package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.example.data.model.DocumentCategory
import com.example.data.repository.DocumentRepository
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
    val detectedOcrText: String = ""
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

    fun setPagesPendingEdit(pages: List<Pair<String, String>>) {
        _uiState.update { it.copy(pagesPendingEdit = pages) }
    }

    fun setImportedUrisPendingEdit(uris: List<Uri>) {
        _uiState.update { it.copy(importedUrisPending = uris, pagesPendingEdit = emptyList()) }
        processImportedUris(uris)
    }

    private fun processImportedUris(uris: List<Uri>) {
        val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
            .setInputData(workDataOf(
                ImageProcessingWorker.KEY_OPERATION to ImageProcessingWorker.OP_IMPORT,
                ImageProcessingWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray()
            ))
            .build()

        _uiState.update { it.copy(isLoading = true) }
        
        workManager.enqueueUniqueWork(
            "import_images_${System.currentTimeMillis()}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collectLatest { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val resultStrings = workInfo.outputData.getStringArray(ImageProcessingWorker.KEY_RESULT_PAGES)
                            val processedPages = resultStrings?.map {
                                val parts = it.split("|")
                                Pair(parts[0], parts[1])
                            } ?: emptyList()
                            _uiState.update { it.copy(pagesPendingEdit = processedPages, isLoading = false) }
                        }
                        WorkInfo.State.FAILED -> {
                            _uiState.update { it.copy(isLoading = false) }
                            viewModelScope.launch { _events.send(UiEvent.Error("Failed to process imported images")) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

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

    fun clearPendingPages() {
        _uiState.update { it.copy(pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList()) }
    }
}
