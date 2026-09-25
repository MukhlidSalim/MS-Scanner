package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.DetectionResult
import com.example.engine.cv.DocumentDetector
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanMode {
    SINGLE,
    BATCH,
    ID_CARD,
    RECEIPT
}

data class StagedPage(
    val rawPath: String,
    val processedPath: String,
    val quad: DocumentQuad
)

data class ScanUiState(
    val mode: ScanMode = ScanMode.SINGLE,
    val isAutoCaptureEnabled: Boolean = true,
    val flashMode: Int = 0, // 0: off, 1: on, 2: auto
    val isGridVisible: Boolean = false,
    val isProcessingCapture: Boolean = false,
    val detection: DetectionResult = DetectionResult(
        quad = DocumentQuad.defaultQuad(),
        isDetected = false,
        isStable = false,
        isCentered = false,
        stabilityProgress = 0f,
        guidanceEn = "Align document inside frame",
        guidanceAr = "وجّه الكاميرا نحو حدود المستند"
    ),
    val stagedPages: List<StagedPage> = emptyList(),
    val idCardStep: Int = 0 // 0: Front, 1: Back
)

class ScanViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val detector = DocumentDetector()
    private var lastAutoCaptureTime = 0L

    fun setScanMode(mode: ScanMode) {
        _uiState.value = _uiState.value.copy(mode = mode, idCardStep = 0)
        detector.reset()
    }

    fun toggleAutoCapture() {
        _uiState.value = _uiState.value.copy(isAutoCaptureEnabled = !_uiState.value.isAutoCaptureEnabled)
        detector.reset()
    }

    fun toggleFlash() {
        val next = (_uiState.value.flashMode + 1) % 3
        _uiState.value = _uiState.value.copy(flashMode = next)
    }

    fun toggleGrid() {
        _uiState.value = _uiState.value.copy(isGridVisible = !_uiState.value.isGridVisible)
    }

    fun onFrameAnalyzed(bitmap: Bitmap, onAutoCaptureTrigger: () -> Unit) {
        if (_uiState.value.isProcessingCapture) return

        val result = detector.processFrame(bitmap)
        _uiState.value = _uiState.value.copy(detection = result)

        val now = System.currentTimeMillis()
        if (_uiState.value.isAutoCaptureEnabled && result.isStable && now - lastAutoCaptureTime > 2500) {
            lastAutoCaptureTime = now
            onAutoCaptureTrigger()
        }
    }

    fun onCaptureCompleted(fullResBitmap: Bitmap, onFinishedSingle: (Long) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingCapture = true)
            try {
                val currentQuad = _uiState.value.detection.quad

                // Save raw capture
                val rawPath = ImageProcessor.saveBitmapToFile(context, fullResBitmap, "raw_")

                // Warp and apply default Magic filter
                val warped = ImageProcessor.warpPerspective(fullResBitmap, currentQuad)
                val processed = ImageProcessor.applyFilter(warped, com.example.data.model.FilterType.MAGIC)
                val processedPath = ImageProcessor.saveBitmapToFile(context, processed, "proc_")

                // Free memory
                if (fullResBitmap != warped) fullResBitmap.recycle()
                if (warped != processed) warped.recycle()
                processed.recycle()

                val newPage = StagedPage(rawPath, processedPath, currentQuad)
                val updatedList = _uiState.value.stagedPages + newPage

                if (_uiState.value.mode == ScanMode.SINGLE) {
                    val docId = repository.createDocumentWithPages(
                        title = "",
                        pages = listOf(Pair(rawPath, processedPath))
                    )
                    _uiState.value = _uiState.value.copy(isProcessingCapture = false, stagedPages = emptyList())
                    onFinishedSingle(docId)
                } else if (_uiState.value.mode == ScanMode.ID_CARD) {
                    if (_uiState.value.idCardStep == 0) {
                        _uiState.value = _uiState.value.copy(
                            isProcessingCapture = false,
                            stagedPages = updatedList,
                            idCardStep = 1
                        )
                    } else {
                        val docId = repository.createDocumentWithPages(
                            title = "ID Card",
                            category = "ID_CARD",
                            pages = updatedList.map { Pair(it.rawPath, it.processedPath) }
                        )
                        _uiState.value = _uiState.value.copy(isProcessingCapture = false, stagedPages = emptyList(), idCardStep = 0)
                        onFinishedSingle(docId)
                    }
                } else {
                    // Batch mode: add to staged tray and continue scanning
                    _uiState.value = _uiState.value.copy(
                        isProcessingCapture = false,
                        stagedPages = updatedList
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessingCapture = false)
            }
        }
    }

    fun finishBatchScanning(onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.stagedPages
            if (pages.isEmpty()) return@launch
            _uiState.value = _uiState.value.copy(isProcessingCapture = true)
            val docId = repository.createDocumentWithPages(
                title = "",
                pages = pages.map { Pair(it.rawPath, it.processedPath) }
            )
            _uiState.value = _uiState.value.copy(isProcessingCapture = false, stagedPages = emptyList())
            onComplete(docId)
        }
    }

    fun removeStagedPage(index: Int) {
        val list = _uiState.value.stagedPages.toMutableList()
        if (index in list.indices) {
            val removed = list.removeAt(index)
            try {
                java.io.File(removed.rawPath).delete()
                java.io.File(removed.processedPath).delete()
            } catch (e: Exception) { /* ignore */ }
            _uiState.value = _uiState.value.copy(stagedPages = list)
        }
    }
}
