package com.example.engine.cv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class BatchItemStatus {
    PENDING,        // Waiting in queue
    ANALYZING,      // Document border detection
    CROPPING,       // Perspective warp & filter
    COMPLETED,      // Processed and ready
    FAILED,         // Error during processing
    SKIPPED         // Auto-crop skipped by the user (page kept uncropped)
}

data class BatchCropItemState(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val sourceUri: Uri? = null,
    val sourceFilePath: String? = null,
    val rawImagePath: String = "",
    val processedImagePath: String = "",
    val detectedQuad: DocumentQuad? = null,
    val isAutoCropped: Boolean = false,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val progress: Float = 0f,
    val statusTextEn: String = "Waiting in queue...",
    val statusTextAr: String = "في الانتظار...",
    val errorMessage: String? = null
)

data class BatchQueueState(
    val isProcessing: Boolean = false,
    val isPaused: Boolean = false,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val currentProcessingIndex: Int = -1,
    val currentItemStatusEn: String = "",
    val currentItemStatusAr: String = "",
    val items: List<BatchCropItemState> = emptyList(),
    val autoCropEnabled: Boolean = true
) {
    val progressPercentage: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f
}

/**
 * Non-blocking batch queue for imported / existing pages. All image work is delegated to
 * [DocumentPipeline] (same detection, warp and filter as single scans), so batch output can no longer
 * diverge from the single-page path. Pages are emitted one by one as soon as they are ready, so the
 * UI (and the Edit screen) stays usable while the rest of the batch runs.
 */
class BatchProcessingQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _queueState = MutableStateFlow(BatchQueueState())
    val queueState: StateFlow<BatchQueueState> = _queueState.asStateFlow()

    private var queueJob: Job? = null
    private val paused = MutableStateFlow(false)
    @Volatile private var skipRemainingAutoCrop = false

    // ------------------------------------------------------------------ enqueue

    /**
     * Imported images (gallery multi-select, shares).
     * [onPageReady] runs on the main thread for each finished page, in order.
     */
    fun enqueueUris(
        context: Context,
        uris: List<Uri>,
        autoCrop: Boolean = true,
        onPageReady: suspend (index: Int, rawPath: String, procPath: String, quad: DocumentQuad?) -> Unit,
        onAllComplete: suspend () -> Unit = {}
    ) {
        if (uris.isEmpty()) return
        val appContext = context.applicationContext
        startNewRun(
            items = uris.mapIndexed { i, uri -> BatchCropItemState(index = i, sourceUri = uri) },
            autoCrop = autoCrop,
            titleEn = "Preparing imported documents...",
            titleAr = "جاري تحضير المستندات المستوردة..."
        )
        queueJob = scope.launch {
            val total = uris.size
            for (i in 0 until total) {
                ensureActive()
                awaitIfPaused()
                val doCrop = autoCrop && !skipRemainingAutoCrop
                markAnalyzing(i, total)
                try {
                    val page = DocumentPipeline.processUri(appContext, uris[i], doCrop, null, DocumentPipeline.DEFAULT_FILTER, "batch_p${i + 1}")
                    if (page == null) {
                        markItemFailed(i, "Could not decode image")
                        continue
                    }
                    markCompleted(i, page, doCrop)
                    withContext(Dispatchers.Main) {
                        onPageReady(i, page.rawPath, page.processedPath, page.quad.takeIf { page.autoCropped })
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    markItemFailed(i, e.localizedMessage ?: "Unknown error")
                }
            }
            finishRun("Batch processing finished", "تم الانتهاء من المعالجة")
            withContext(Dispatchers.Main) { onAllComplete() }
        }
    }

    /** Re-detect + re-render already loaded pages (raw, processed) pairs. */
    fun enqueueExistingPagePairs(
        context: Context,
        pages: List<Pair<String, String>>,
        onPageUpdated: suspend (index: Int, newProcPath: String, quad: DocumentQuad) -> Unit,
        onAllComplete: suspend () -> Unit = {}
    ) {
        if (pages.isEmpty()) return
        val appContext = context.applicationContext
        startNewRun(
            items = pages.mapIndexed { i, p -> BatchCropItemState(index = i, rawImagePath = p.first, processedImagePath = p.second) },
            autoCrop = true,
            titleEn = "Re-analyzing documents for auto-crop...",
            titleAr = "جاري إعادة تحليل وقص المستندات..."
        )
        queueJob = scope.launch {
            val total = pages.size
            for (i in 0 until total) {
                ensureActive()
                awaitIfPaused()
                if (skipRemainingAutoCrop) {
                    updateItemState(i) { it.copy(status = BatchItemStatus.SKIPPED, progress = 1f, statusTextEn = "Skipped", statusTextAr = "تم التخطي") }
                    _queueState.update { it.copy(completedCount = it.completedCount + 1) }
                    continue
                }
                val rawPath = pages[i].first.ifBlank { pages[i].second }
                if (!File(rawPath).exists()) {
                    markItemFailed(i, "Raw file not found")
                    continue
                }
                markAnalyzing(i, total)
                try {
                    val page = DocumentPipeline.redetect(appContext, rawPath, null, 0, DocumentPipeline.DEFAULT_FILTER, "batch_recrop_p${i + 1}")
                    if (page == null) {
                        markItemFailed(i, "Failed to load bitmap")
                        continue
                    }
                    markCompleted(i, page, true)
                    withContext(Dispatchers.Main) { onPageUpdated(i, page.processedPath, page.quad) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    markItemFailed(i, e.localizedMessage ?: "Processing error")
                }
            }
            finishRun("Auto-crop finished", "تم الانتهاء من القص التلقائي")
            withContext(Dispatchers.Main) { onAllComplete() }
        }
    }

    // ------------------------------------------------------------------ controls

    fun pause() {
        paused.value = true
        _queueState.update { it.copy(isPaused = true) }
    }

    fun resume() {
        paused.value = false
        _queueState.update { it.copy(isPaused = false) }
    }

    /** Remaining pages are imported without auto-crop (kept whole, still editable manually). */
    fun skipRemainingAutoCrop() {
        skipRemainingAutoCrop = true
        _queueState.update { it.copy(autoCropEnabled = false) }
    }

    fun cancel() {
        queueJob?.cancel()
        queueJob = null
        paused.value = false
        _queueState.update { it.copy(isProcessing = false, isPaused = false, currentProcessingIndex = -1) }
    }

    fun reset() {
        cancel()
        _queueState.value = BatchQueueState()
    }

    /** Call from the owner's onCleared()/dispose. */
    fun release() {
        cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------------ internals

    private fun startNewRun(items: List<BatchCropItemState>, autoCrop: Boolean, titleEn: String, titleAr: String) {
        queueJob?.cancel()
        paused.value = false
        skipRemainingAutoCrop = false
        _queueState.value = BatchQueueState(
            isProcessing = true,
            totalCount = items.size,
            currentProcessingIndex = 0,
            currentItemStatusEn = titleEn,
            currentItemStatusAr = titleAr,
            items = items,
            autoCropEnabled = autoCrop
        )
    }

    private suspend fun awaitIfPaused() {
        if (paused.value) paused.first { !it }
    }

    private fun markAnalyzing(i: Int, total: Int) {
        updateItemState(i) {
            it.copy(
                status = BatchItemStatus.ANALYZING,
                progress = 0.3f,
                statusTextEn = "Detecting document edges...",
                statusTextAr = "تحليل حواف المستند..."
            )
        }
        _queueState.update {
            it.copy(
                currentProcessingIndex = i,
                currentItemStatusEn = "Analyzing page ${i + 1} of $total...",
                currentItemStatusAr = "تحليل الصفحة ${i + 1} من $total..."
            )
        }
    }

    private fun markCompleted(i: Int, page: ProcessedPage, cropRequested: Boolean) {
        updateItemState(i) {
            it.copy(
                status = if (cropRequested) BatchItemStatus.COMPLETED else BatchItemStatus.SKIPPED,
                progress = 1f,
                rawImagePath = page.rawPath,
                processedImagePath = page.processedPath,
                detectedQuad = page.quad.takeIf { page.autoCropped },
                isAutoCropped = page.autoCropped,
                statusTextEn = if (page.autoCropped) "Auto-cropped" else "Ready (not cropped)",
                statusTextAr = if (page.autoCropped) "تم القص بنجاح" else "جاهزة (بدون قص)"
            )
        }
        _queueState.update {
            it.copy(
                completedCount = it.completedCount + 1,
                currentItemStatusEn = "Page ${i + 1} ready",
                currentItemStatusAr = "الصفحة ${i + 1} جاهزة"
            )
        }
    }

    private fun markItemFailed(i: Int, message: String) {
        updateItemState(i) {
            it.copy(
                status = BatchItemStatus.FAILED,
                progress = 1f,
                errorMessage = message,
                statusTextEn = "Failed: $message",
                statusTextAr = "فشلت المعالجة"
            )
        }
        _queueState.update { it.copy(completedCount = it.completedCount + 1) }
    }

    private fun finishRun(en: String, ar: String) {
        _queueState.update {
            it.copy(isProcessing = false, isPaused = false, currentProcessingIndex = -1, currentItemStatusEn = en, currentItemStatusAr = ar)
        }
    }

    private fun updateItemState(index: Int, transform: (BatchCropItemState) -> BatchCropItemState) {
        _queueState.update { state ->
            if (index !in state.items.indices) state
            else state.copy(items = state.items.toMutableList().also { it[index] = transform(it[index]) })
        }
    }
}
