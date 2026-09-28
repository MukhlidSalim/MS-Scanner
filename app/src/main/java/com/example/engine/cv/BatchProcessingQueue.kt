package com.example.engine.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.example.data.model.FilterType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.util.UUID

enum class BatchItemStatus {
    PENDING,        // Waiting in queue
    ANALYZING,      // Background image analysis (edge detection & quad contouring)
    CROPPING,       // Perspective warp & filter enhancement
    COMPLETED,      // Processed and ready for review/saving
    FAILED,         // Error during processing (fallback to raw image)
    SKIPPED         // User skipped auto-crop
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

    val isAllCompleted: Boolean
        get() = totalCount > 0 && completedCount >= totalCount
}

/**
 * High-performance, non-blocking batch processing queue for auto-cropping imported multi-page documents.
 * Runs heavy image analysis (Sobel edge detection, corner detection, perspective warp, scan enhancements)
 * on background threads (Dispatchers.Default / IO) while keeping the Compose UI 100% fluid and responsive.
 * Emits results page-by-page as each image finishes analysis, enabling immediate user interaction.
 */
class BatchProcessingQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val _queueState = MutableStateFlow(BatchQueueState())
    val queueState: StateFlow<BatchQueueState> = _queueState.asStateFlow()

    private var queueJob: Job? = null
    private var isPausedInternal = false
    private var pauseCompleter: CompletableDeferred<Unit>? = null
    private var skipRemainingAutoCrop = false

    /**
     * Enqueue a list of imported image URIs (e.g. from gallery multi-selection).
     * @param onPageReady Called immediately on the main-safe thread when each individual page completes auto-cropping.
     * @param onAllComplete Called when all pages in the batch have been processed.
     */
    fun enqueueUris(
        context: Context,
        uris: List<Uri>,
        autoCrop: Boolean = true,
        onPageReady: suspend (index: Int, rawPath: String, procPath: String, quad: DocumentQuad?) -> Unit,
        onAllComplete: suspend () -> Unit = {}
    ) {
        if (uris.isEmpty()) return

        // Cancel previous job if running
        queueJob?.cancel()
        isPausedInternal = false
        skipRemainingAutoCrop = false
        pauseCompleter = null

        val initialItems = uris.mapIndexed { idx, uri ->
            BatchCropItemState(
                index = idx,
                sourceUri = uri,
                status = BatchItemStatus.PENDING,
                statusTextEn = "Waiting in queue...",
                statusTextAr = "في الانتظار..."
            )
        }

        _queueState.value = BatchQueueState(
            isProcessing = true,
            isPaused = false,
            totalCount = uris.size,
            completedCount = 0,
            currentProcessingIndex = 0,
            currentItemStatusEn = "Preparing imported documents...",
            currentItemStatusAr = "جاري تحضير المستندات المستوردة...",
            items = initialItems,
            autoCropEnabled = autoCrop
        )

        queueJob = scope.launch(Dispatchers.IO) {
            val total = uris.size
            for (i in 0 until total) {
                if (!isActive) break

                // Check pause state
                if (isPausedInternal) {
                    val deferred = CompletableDeferred<Unit>()
                    pauseCompleter = deferred
                    _queueState.update { it.copy(isPaused = true) }
                    deferred.await()
                    _queueState.update { it.copy(isPaused = false) }
                }

                val uri = uris[i]
                val shouldAutoCropThis = autoCrop && !skipRemainingAutoCrop

                // Update item status: ANALYZING
                updateItemState(i) {
                    it.copy(
                        status = BatchItemStatus.ANALYZING,
                        progress = 0.2f,
                        statusTextEn = "Analyzing document boundaries & edges...",
                        statusTextAr = "تحليل حدود وزوايا المستند..."
                    )
                }
                _queueState.update {
                    it.copy(
                        currentProcessingIndex = i,
                        currentItemStatusEn = "Analyzing page ${i + 1} of $total...",
                        currentItemStatusAr = "تحليل الصفحة ${i + 1} من $total..."
                    )
                }

                try {
                    // 1. Decode bitmap with inSampleSize to prevent OOM
                    val (rawBmp, orientationDegrees) = decodeSampledBitmapFromUri(context, uri, 2048, 2048)
                    if (rawBmp == null) {
                        markItemFailed(i, "Could not decode image")
                        continue
                    }

                    // Rotate if EXIF orientation dictates
                    var cleanRawBmp = if (orientationDegrees != 0) {
                        val rotated = ImageProcessor.rotateBitmap(rawBmp, orientationDegrees)
                        if (rotated != rawBmp) rawBmp.recycle()
                        rotated
                    } else {
                        rawBmp
                    }

                    // Save raw uncropped image
                    val rawPath = ImageProcessor.saveBitmapToFile(context, cleanRawBmp, "batch_raw_p${i + 1}_")

                    // 2. Background Image Analysis: Detect Quad Corners
                    val detectedQuad = if (shouldAutoCropThis) {
                        ImageProcessor.detectDocumentQuad(cleanRawBmp)
                    } else {
                        DocumentQuad.defaultQuad()
                    }

                    val isQuadValid = shouldAutoCropThis && ImageProcessor.isQuadValid(detectedQuad)

                    // 3. Perspective Warp & Auto-Crop
                    updateItemState(i) {
                        it.copy(
                            status = BatchItemStatus.CROPPING,
                            progress = 0.6f,
                            statusTextEn = "Perspective cropping & enhancing...",
                            statusTextAr = "قص المنظور وتطبيق الفلتر..."
                        )
                    }

                    val croppedBmp = if (isQuadValid) {
                        ImageProcessor.applyPerspectiveWarp(cleanRawBmp, detectedQuad)
                    } else {
                        cleanRawBmp.copy(cleanRawBmp.config ?: Bitmap.Config.ARGB_8888, true)
                    }

                    // 4. Scan Filter Enhancement
                    val enhancedBmp = ImageProcessor.applyFilter(croppedBmp, FilterType.MAGIC)
                    val procPath = ImageProcessor.saveBitmapToFile(context, enhancedBmp, "batch_proc_p${i + 1}_")

                    // Recycle temporary bitmaps to free native memory
                    if (croppedBmp != cleanRawBmp) croppedBmp.recycle()
                    if (enhancedBmp != croppedBmp) enhancedBmp.recycle()
                    cleanRawBmp.recycle()

                    // 5. Page Completed: Update state & notify UI immediately
                    updateItemState(i) {
                        it.copy(
                            status = BatchItemStatus.COMPLETED,
                            progress = 1.0f,
                            rawImagePath = rawPath,
                            processedImagePath = procPath,
                            detectedQuad = detectedQuad,
                            isAutoCropped = isQuadValid,
                            statusTextEn = "Ready",
                            statusTextAr = "جاهزة"
                        )
                    }

                    _queueState.update { state ->
                        state.copy(
                            completedCount = state.completedCount + 1,
                            currentItemStatusEn = "Page ${i + 1} ready",
                            currentItemStatusAr = "الصفحة ${i + 1} جاهزة"
                        )
                    }

                    // Emit to caller so UI displays this page right away
                    withContext(Dispatchers.Main) {
                        onPageReady(i, rawPath, procPath, detectedQuad)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    markItemFailed(i, e.localizedMessage ?: "Unknown error")
                }
            }

            _queueState.update {
                it.copy(
                    isProcessing = false,
                    currentProcessingIndex = -1,
                    currentItemStatusEn = "Batch auto-crop completed",
                    currentItemStatusAr = "اكتملت معالجة الدفعة بنجاح"
                )
            }

            withContext(Dispatchers.Main) {
                onAllComplete()
            }
        }
    }

    /**
     * Enqueue already loaded pages (raw/proc pairs) for batch auto-cropping re-analysis in background.
     */
    fun enqueueExistingPagePairs(
        context: Context,
        pages: List<Pair<String, String>>,
        onPageUpdated: suspend (index: Int, newProcPath: String, quad: DocumentQuad) -> Unit,
        onAllComplete: suspend () -> Unit = {}
    ) {
        if (pages.isEmpty()) return

        queueJob?.cancel()
        isPausedInternal = false
        skipRemainingAutoCrop = false
        pauseCompleter = null

        val initialItems = pages.mapIndexed { idx, pair ->
            BatchCropItemState(
                index = idx,
                rawImagePath = pair.first,
                processedImagePath = pair.second,
                status = BatchItemStatus.PENDING,
                statusTextEn = "Waiting in queue...",
                statusTextAr = "في الانتظار..."
            )
        }

        _queueState.value = BatchQueueState(
            isProcessing = true,
            isPaused = false,
            totalCount = pages.size,
            completedCount = 0,
            currentProcessingIndex = 0,
            currentItemStatusEn = "Re-analyzing documents for auto-crop...",
            currentItemStatusAr = "جاري إعادة تحليل وقص المستندات...",
            items = initialItems,
            autoCropEnabled = true
        )

        queueJob = scope.launch(Dispatchers.IO) {
            val total = pages.size
            for (i in 0 until total) {
                if (!isActive) break

                if (isPausedInternal) {
                    val deferred = CompletableDeferred<Unit>()
                    pauseCompleter = deferred
                    _queueState.update { it.copy(isPaused = true) }
                    deferred.await()
                    _queueState.update { it.copy(isPaused = false) }
                }

                val rawPath = pages[i].first.ifBlank { pages[i].second }
                val rawFile = File(rawPath)
                if (!rawFile.exists()) {
                    markItemFailed(i, "Raw file not found")
                    continue
                }

                updateItemState(i) {
                    it.copy(
                        status = BatchItemStatus.ANALYZING,
                        progress = 0.3f,
                        statusTextEn = "Detecting document edges...",
                        statusTextAr = "تحليل حواف المستند..."
                    )
                }

                try {
                    val rawBmp = ImageProcessor.loadBitmapFromFile(rawPath, 2048)
                    if (rawBmp == null) {
                        markItemFailed(i, "Failed to load bitmap")
                        continue
                    }

                    val quad = ImageProcessor.detectDocumentQuad(rawBmp)
                    val isQuadValid = ImageProcessor.isQuadValid(quad)

                    updateItemState(i) {
                        it.copy(
                            status = BatchItemStatus.CROPPING,
                            progress = 0.7f,
                            statusTextEn = "Perspective cropping & enhancing...",
                            statusTextAr = "قص المنظور وتطبيق الفلتر..."
                        )
                    }

                    val warped = if (isQuadValid) {
                        ImageProcessor.applyPerspectiveWarp(rawBmp, quad)
                    } else {
                        rawBmp.copy(rawBmp.config ?: Bitmap.Config.ARGB_8888, true)
                    }

                    val enhanced = ImageProcessor.applyFilter(warped, FilterType.MAGIC)
                    val newProcPath = ImageProcessor.saveBitmapToFile(context, enhanced, "batch_recrop_p${i + 1}_")

                    if (warped != rawBmp) warped.recycle()
                    if (enhanced != warped) enhanced.recycle()
                    rawBmp.recycle()

                    updateItemState(i) {
                        it.copy(
                            status = BatchItemStatus.COMPLETED,
                            progress = 1.0f,
                            processedImagePath = newProcPath,
                            detectedQuad = quad,
                            isAutoCropped = isQuadValid,
                            statusTextEn = "Auto-cropped",
                            statusTextAr = "تم القص بنجاح"
                        )
                    }

                    _queueState.update { state ->
                        state.copy(completedCount = state.completedCount + 1)
                    }

                    withContext(Dispatchers.Main) {
                        onPageUpdated(i, newProcPath, quad)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    markItemFailed(i, e.localizedMessage ?: "Processing error")
                }
            }

            _queueState.update {
                it.copy(
                    isProcessing = false,
                    currentProcessingIndex = -1,
                    currentItemStatusEn = "Auto-crop finished",
                    currentItemStatusAr = "تم الانتهاء من القص التلقائي"
                )
            }

            withContext(Dispatchers.Main) {
                onAllComplete()
            }
        }
    }

    fun pause() {
        isPausedInternal = true
        _queueState.update { it.copy(isPaused = true) }
    }

    fun resume() {
        isPausedInternal = false
        pauseCompleter?.complete(Unit)
        pauseCompleter = null
        _queueState.update { it.copy(isPaused = false) }
    }

    fun skipRemaining() {
        skipRemainingAutoCrop = true
    }

    fun cancel() {
        queueJob?.cancel()
        pauseCompleter?.complete(Unit)
        pauseCompleter = null
        isPausedInternal = false
        _queueState.update {
            it.copy(
                isProcessing = false,
                isPaused = false,
                currentItemStatusEn = "Batch queue cancelled",
                currentItemStatusAr = "تم إلغاء الطابور"
            )
        }
    }

    fun reset() {
        cancel()
        _queueState.value = BatchQueueState()
    }

    private fun updateItemState(index: Int, transform: (BatchCropItemState) -> BatchCropItemState) {
        _queueState.update { state ->
            val updatedList = state.items.toMutableList()
            if (index in updatedList.indices) {
                updatedList[index] = transform(updatedList[index])
            }
            state.copy(items = updatedList)
        }
    }

    private fun markItemFailed(index: Int, error: String) {
        updateItemState(index) {
            it.copy(
                status = BatchItemStatus.FAILED,
                progress = 1.0f,
                errorMessage = error,
                statusTextEn = "Analysis skipped: $error",
                statusTextAr = "تم تخطي التحليل: $error"
            )
        }
        _queueState.update { state ->
            state.copy(completedCount = state.completedCount + 1)
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Pair<Bitmap?, Int> {
        // Step 1: Decode bounds
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        val sampleSize = calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, reqWidth, reqHeight)

        // Step 2: Read EXIF orientation
        var orientationDegrees = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                orientationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            // Ignore EXIF failure
        }

        // Step 3: Decode scaled bitmap
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }

        return Pair(bitmap, orientationDegrees)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
