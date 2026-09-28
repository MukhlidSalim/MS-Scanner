package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DocumentCategory
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.engine.ocr.DocumentAiEngine
import com.example.ui.util.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun setPagesPendingEdit(pages: List<Pair<String, String>>) {
        _uiState.update { it.copy(pagesPendingEdit = pages, pendingPages = pages, importedUrisPending = emptyList()) }
    }

    fun setImportedUrisPendingEdit(uris: List<Uri>, autoCrop: Boolean = true) {
        if (uris.isEmpty()) return
        _uiState.update { it.copy(importedUrisPending = uris, pagesPendingEdit = emptyList(), pendingPages = emptyList(), isLoading = true) }
        viewModelScope.launch {
            val completedPages = mutableListOf<Pair<String, String>>()
            for ((index, uri) in uris.withIndex()) {
                try {
                    val result = processImportedUri(uri, index, autoCrop)
                    if (result != null) {
                        completedPages += result
                        _uiState.update { it.copy(pagesPendingEdit = completedPages.toList(), pendingPages = completedPages.toList()) }
                        if (index == 0) analyzePendingFirstPage()
                    } else {
                        _events.send(UiEvent.Error("Could not import page ${index + 1}"))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _events.send(UiEvent.Error("Failed to process page ${index + 1}"))
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun processImportedUri(uri: Uri, index: Int, autoCrop: Boolean): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val decodedResult = decodeSampledBitmapFromUri(context, uri, 2048, 2048)
            val decoded = decodedResult.first ?: return@withContext null
            var rawBitmap: Bitmap = decoded
            try {
                val orientation = decodedResult.second
                if (orientation != 0) {
                    val rotated = ImageProcessor.rotateBitmap(rawBitmap, orientation)
                    if (rotated !== rawBitmap) rawBitmap.recycle()
                    rawBitmap = rotated
                }
                val rawPath = ImageProcessor.saveBitmapToFile(context, rawBitmap, "import_raw_p${index + 1}_")
                val quad = if (autoCrop) ImageProcessor.detectDocumentQuad(rawBitmap) else DocumentQuad.fullQuad()
                val valid = autoCrop && ImageProcessor.isQuadValid(quad)
                val cropped = if (valid) ImageProcessor.applyPerspectiveWarp(rawBitmap, quad)
                              else rawBitmap.copy(rawBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                try {
                    val filtered = ImageProcessor.applyFilter(cropped, com.example.data.model.FilterType.MAGIC)
                    try {
                        val procPath = ImageProcessor.saveBitmapToFile(context, filtered, "import_proc_p${index + 1}_")
                        return@withContext Pair(rawPath, procPath)
                    } finally { if (filtered !== cropped) filtered.recycle() }
                } finally { if (cropped !== rawBitmap) cropped.recycle() }
            } finally { rawBitmap.recycle() }
        }

    fun autoCropAllPendingPages() {
        val currentPages = _uiState.value.pagesPendingEdit
        if (currentPages.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val updated = currentPages.toMutableList()
                for (index in currentPages.indices) {
                    val (rawPath, oldProcessed) = currentPages[index]
                    val bmp = ImageProcessor.loadBitmapFromFile(rawPath, 2048) ?: continue
                    try {
                        val quad = ImageProcessor.detectDocumentQuad(bmp)
                        val valid = ImageProcessor.isQuadValid(quad)
                        val warped = if (valid) ImageProcessor.applyPerspectiveWarp(bmp, quad)
                                     else bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
                        try {
                            val filtered = ImageProcessor.applyFilter(warped, com.example.data.model.FilterType.MAGIC)
                            try {
                                val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "recrop_p${index + 1}_")
                                if (newPath.isNotBlank()) updated[index] = Pair(rawPath, newPath)
                            } finally { if (filtered !== warped) filtered.recycle() }
                        } finally { if (warped !== bmp) warped.recycle() }
                    } finally { bmp.recycle() }
                    if (updated[index].second != oldProcessed) {
                        _uiState.update { it.copy(pagesPendingEdit = updated.toList(), pendingPages = updated.toList()) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to auto-crop pending pages"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun rotatePendingPage(index: Int, clockwise: Boolean) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index !in pages.indices) return
        val pagePair = pages[index]
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val bmp = ImageProcessor.loadBitmapFromFile(pagePair.second, 2048)
                    ?: throw IllegalStateException("Unable to load pending page")
                try {
                    val rotated = ImageProcessor.rotateBitmap(bmp, if (clockwise) 90 else -90)
                    try {
                        val newPath = ImageProcessor.saveBitmapToFile(context, rotated, "rot_pending_")
                        pages[index] = Pair(pagePair.first, newPath)
                        _uiState.update { it.copy(pagesPendingEdit = pages.toList(), pendingPages = pages.toList()) }
                    } finally { if (rotated !== bmp) rotated.recycle() }
                } finally { bmp.recycle() }
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to rotate pending page"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun analyzePendingFirstPage() {
        val pages = _uiState.value.pagesPendingEdit
        if (pages.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bmp = ImageProcessor.loadBitmapFromFile(pages.first().second, 1200) ?: return@launch
                try {
                    val result = DocumentAiEngine.performOfflineOcr(bmp)
                    _uiState.update { it.copy(detectedCategory = result.detectedCategory, detectedOcrText = result.fullText) }
                } finally { bmp.recycle() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importPagesAsDocument(title: String = "", selectedFolder: String = "Default", onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.pagesPendingEdit
            if (pages.isEmpty()) return@launch
            try {
                val docId = repository.createDocumentWithPages(
                    title = title, folderName = selectedFolder,
                    category = _uiState.value.detectedCategory.name,
                    ocrText = _uiState.value.detectedOcrText, pages = pages
                )
                clearPendingPages()
                _uiState.update { it.copy(detectedCategory = DocumentCategory.OTHER, detectedOcrText = "") }
                onComplete(docId)
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to save imported document"))
            }
        }
    }

    fun updatePendingPageProcessedImage(index: Int, newPath: String) {
        val pages = _uiState.value.pagesPendingEdit.toMutableList()
        if (index in pages.indices) {
            pages[index] = Pair(pages[index].first, newPath)
            _uiState.update { it.copy(pagesPendingEdit = pages.toList(), pendingPages = pages.toList()) }
        }
    }

    fun commitPendingPagesToDocument(docId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.pagesPendingEdit
            if (pages.isEmpty()) return@launch
            try {
                val doc = repository.getDocumentById(docId) ?: return@launch
                var pageCount = doc.pageCount
                for ((rawPath, procPath) in pages) {
                    repository.insertPage(com.example.data.model.PageEntity(
                        documentId = doc.id, pageIndex = pageCount, rawImagePath = rawPath, processedImagePath = procPath
                    ))
                    pageCount++
                }
                repository.updateDocument(doc.copy(pageCount = pageCount))
                clearPendingPages()
                onComplete()
            } catch (e: Exception) {
                e.printStackTrace()
                _events.send(UiEvent.Error("Failed to save pages to document"))
            }
        }
    }

    fun clearPendingPages(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            val state = _uiState.value
            (state.pendingPages + state.pagesPendingEdit)
                .flatMap { listOf(it.first, it.second) }
                .distinct()
                .forEach { if (it.isNotBlank()) java.io.File(it).delete() }
        }
        _uiState.update { it.copy(pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList()) }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context, uri: Uri, reqWidth: Int, reqHeight: Int
    ): Pair<Bitmap?, Int> {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        var sampleSize = 1
        var halfHeight = boundsOptions.outHeight / 2
        var halfWidth = boundsOptions.outWidth / 2
        while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) sampleSize *= 2

        var orientationDegrees = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val orientation = ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                orientationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Exception) { }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return Pair(bitmap, orientationDegrees)
    }
}
