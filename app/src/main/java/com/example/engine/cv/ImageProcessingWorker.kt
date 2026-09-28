package com.example.engine.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.db.DocScanDatabase
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.repository.DocumentRepository
import com.example.engine.ocr.DocumentAiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Worker for offloading heavy image processing tasks to the background.
 * Prevents UI freezes and ensures processing continues even if the app is closed.
 */
class ImageProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository: DocumentRepository by lazy {
        val database = DocScanDatabase.getInstance(applicationContext)
        DocumentRepository(applicationContext, database.documentDao())
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operation = inputData.getString(KEY_OPERATION) ?: return@withContext Result.failure()
        
        try {
            when (operation) {
                OP_IMPORT -> handleImport()
                OP_FILTER_ALL -> handleFilterAll()
                OP_CROP_WARP -> handleCropWarp()
                OP_ROTATE -> handleRotate()
                OP_EDIT_PROCESS -> handleEditProcess()
                OP_ROTATE_SESSION -> handleRotateSession()
                OP_ROTATE_PENDING -> handleRotatePending()
                OP_MERGE_GRID -> handleMergeGrid()
                OP_SAVE_BITMAP -> handleSaveBitmap()
                OP_DETECT_QUAD -> handleDetectQuad()
                OP_ROTATE_PAGE -> handleRotate()
                OP_BURN_ANNOTATIONS -> handleBurnAnnotations()
                OP_SAVE_TO_GALLERY -> handleSaveToGallery()
                OP_SAVE_DOC_TO_GALLERY -> handleSaveDocToGallery()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun handleSaveDocToGallery(): Result {
        val docId = inputData.getLong(KEY_DOC_ID, -1L)
        if (docId == -1L) return Result.failure()
        
        val pages = repository.getPagesList(docId)
        if (pages.isEmpty()) return Result.failure()
        
        var successCount = 0
        for (page in pages) {
            val file = File(page.processedImagePath)
            if (file.exists()) {
                if (ImageProcessor.saveToGallery(applicationContext, file)) {
                    successCount++
                }
            }
        }
        
        return if (successCount > 0) Result.success() else Result.failure()
    }

    private suspend fun handleBurnAnnotations(): Result {
        val pageId = inputData.getLong(KEY_PAGE_ID, -1L)
        if (pageId == -1L) return Result.failure()
        
        // This requires access to annotations, which might be in the database or passed as JSON
        // Assuming we have a way to get annotations for a page from the repository
        // For now, let's assume the annotations are passed as JSON or we fetch them
        // Checking AnnotationEntity or similar...
        
        return Result.failure() // Placeholder until I check annotation structure
    }

    private suspend fun handleSaveToGallery(): Result {
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return Result.failure()
        val file = File(imagePath)
        if (!file.exists()) return Result.failure()

        return try {
            val success = ImageProcessor.saveToGallery(applicationContext, file)
            if (success) Result.success() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private suspend fun handleDetectQuad(): Result {
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return Result.failure()
        return try {
            val bmp = ImageProcessor.loadBitmapFromFile(imagePath, 2048)
            if (bmp != null) {
                val quad = ImageProcessor.detectDocumentQuad(bmp)
                bmp.recycle()
                Result.success(workDataOf(KEY_QUAD_JSON to quad.toJson()))
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun handleMergeGrid(): Result {
        val imagePaths = inputData.getStringArray(KEY_IMAGE_PATHS)?.toList() ?: return Result.failure()
        val layoutName = inputData.getString(KEY_LAYOUT) ?: MergeGridLayout.AUTO.name
        val layout = try { MergeGridLayout.valueOf(layoutName) } catch (e: Exception) { MergeGridLayout.AUTO }
        val scale = inputData.getFloat(KEY_SCALE, 1.0f)
        val spacing = inputData.getFloat(KEY_SPACING, 32f)
        val cornerRadius = inputData.getFloat(KEY_CORNER_RADIUS, 16f)
        val hasBorder = inputData.getBoolean(KEY_HAS_BORDER, true)
        val bgColor = inputData.getInt(KEY_BG_COLOR, android.graphics.Color.WHITE)
        val fitModeName = inputData.getString(KEY_FIT_MODE) ?: MergeFitMode.FIT.name
        val fitMode = try { MergeFitMode.valueOf(fitModeName) } catch (e: Exception) { MergeFitMode.FIT }

        return try {
            val mergedPath = ImageProcessor.createMultiImageGridCollage(
                context = applicationContext,
                imagePaths = imagePaths,
                layout = layout,
                imageScale = scale,
                spacingPx = spacing,
                cornerRadiusPx = cornerRadius,
                hasBorder = hasBorder,
                backgroundColor = bgColor,
                fitMode = fitMode,
                outPrefix = "merged_page_"
            )
            if (mergedPath.isNotEmpty()) {
                Result.success(workDataOf(KEY_RESULT_PATH to mergedPath))
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun handleSaveBitmap(): Result {
        val uriStr = inputData.getString(KEY_URI) ?: return Result.failure()
        val uri = Uri.parse(uriStr)
        val prefix = inputData.getString(KEY_PREFIX) ?: "save_"

        return try {
            applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    val path = ImageProcessor.saveBitmapToFile(applicationContext, bmp, prefix)
                    bmp.recycle()
                    Result.success(workDataOf(KEY_RESULT_PATH to path))
                } else {
                    Result.failure()
                }
            } ?: Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun handleEditProcess(): Result {
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return Result.failure()
        val quadJson = inputData.getString(KEY_QUAD_JSON) ?: return Result.failure()
        val quad = DocumentQuad.fromJson(quadJson)
        val rotation = inputData.getInt(KEY_ROTATION_DEGREES, 0)
        val filterName = inputData.getString(KEY_FILTER_TYPE) ?: FilterType.AUTO.name
        val filter = try { FilterType.valueOf(filterName) } catch (e: Exception) { FilterType.AUTO }
        val brightness = inputData.getFloat(KEY_BRIGHTNESS, 0f)
        val contrast = inputData.getFloat(KEY_CONTRAST, 1f)
        val sharpen = inputData.getBoolean(KEY_SHARPEN, false)

        var original: Bitmap? = null
        var rotated: Bitmap? = null
        var warped: Bitmap? = null
        var filtered: Bitmap? = null
        var enhanced: Bitmap? = null

        return try {
            original = ImageProcessor.loadBitmapFromFile(imagePath, 2048) ?: return Result.failure()
            rotated = if (rotation != 0) ImageProcessor.rotateBitmap(original, rotation) else original
            warped = ImageProcessor.applyPerspectiveWarp(rotated, quad)
            filtered = ImageProcessor.applyFilter(warped, filter)
            enhanced = if (brightness != 0f || contrast != 1f || sharpen) {
                ImageProcessor.adjustEnhancements(filtered, brightness, contrast, sharpen)
            } else {
                filtered
            }

            val newPath = ImageProcessor.saveBitmapToFile(applicationContext, enhanced!!, "edit_proc_")
            Result.success(workDataOf(KEY_RESULT_PATH to newPath))
        } finally {
            if (original != rotated) original?.recycle()
            if (rotated != warped) rotated?.recycle()
            if (warped != filtered) warped?.recycle()
            if (filtered != enhanced) filtered?.recycle()
            enhanced?.recycle()
        }
    }

    private suspend fun handleRotateSession(): Result {
        val pageId = inputData.getLong(KEY_PAGE_ID, -1L)
        val clockwise = inputData.getBoolean(KEY_CLOCKWISE, true)
        if (pageId == -1L) return Result.failure()

        val page = repository.getPageById(pageId) ?: return Result.failure()
        val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 2000) ?: return Result.failure()
        
        val degrees = if (clockwise) 90 else -90
        val rotated = ImageProcessor.rotateBitmap(bmp, degrees)
        val newPath = ImageProcessor.saveBitmapToFile(applicationContext, rotated, "rot_session_")
        
        val updatedPage = page.copy(
            processedImagePath = newPath,
            rotationDegrees = (page.rotationDegrees + degrees) % 360
        )
        repository.updatePage(updatedPage)
        
        bmp.recycle()
        rotated.recycle()
        return Result.success()
    }

    private suspend fun handleRotatePending(): Result {
        val rawPath = inputData.getString(KEY_RAW_PATH) ?: return Result.failure()
        val procPath = inputData.getString(KEY_PROC_PATH) ?: return Result.failure()
        val clockwise = inputData.getBoolean(KEY_CLOCKWISE, true)

        val bmp = ImageProcessor.loadBitmapFromFile(procPath, 2000) ?: return Result.failure()
        val degrees = if (clockwise) 90 else -90
        val rotated = ImageProcessor.rotateBitmap(bmp, degrees)
        val newPath = ImageProcessor.saveBitmapToFile(applicationContext, rotated, "rot_pending_")
        
        bmp.recycle()
        rotated.recycle()
        return Result.success(workDataOf(KEY_RESULT_PATH to newPath))
    }

    private suspend fun handleImport(): Result {
        val urisStrings = inputData.getStringArray(KEY_URIS) ?: return Result.failure()
        val uris = urisStrings.map { Uri.parse(it) }
        val processedPages = mutableListOf<String>() // rawPath|procPath pairs

        for (uri in uris) {
            try {
                applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        val rawPath = ImageProcessor.saveBitmapToFile(applicationContext, bmp, "import_raw_")
                        val quad = ImageProcessor.detectDocumentQuad(bmp)
                        val cropped = if (ImageProcessor.isQuadValid(quad)) {
                            ImageProcessor.applyPerspectiveWarp(bmp, quad)
                        } else {
                            bmp
                        }
                        val proc = ImageProcessor.applyFilter(cropped, FilterType.MAGIC)
                        val procPath = ImageProcessor.saveBitmapToFile(applicationContext, proc, "import_proc_")
                        
                        processedPages.add("$rawPath|$procPath")
                        
                        if (cropped != bmp && cropped != proc) cropped.recycle()
                        if (bmp != proc) bmp.recycle()
                        proc.recycle()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Result.success(workDataOf(KEY_RESULT_PAGES to processedPages.toTypedArray()))
    }

    private suspend fun handleFilterAll(): Result {
        val docId = inputData.getLong(KEY_DOC_ID, -1L)
        if (docId == -1L) return Result.failure()
        
        val filterName = inputData.getString(KEY_FILTER_TYPE) ?: return Result.failure()
        val filter = try { FilterType.valueOf(filterName) } catch (e: Exception) { FilterType.MAGIC }
        
        val pages = repository.getPagesList(docId)
        val updatedPages = mutableListOf<PageEntity>()

        for (page in pages) {
            var rawBitmap: Bitmap? = null
            var rotatedRaw: Bitmap? = null
            var filtered: Bitmap? = null
            try {
                rawBitmap = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: continue
                rotatedRaw = ImageProcessor.rotateBitmap(rawBitmap, page.rotationDegrees)
                filtered = ImageProcessor.applyFilter(rotatedRaw, filter)
                val newProcessedPath = ImageProcessor.saveBitmapToFile(applicationContext, filtered, "proc_all_")
                
                // Cleanup old file
                val oldFile = File(page.processedImagePath)
                if (oldFile.exists() && oldFile.absolutePath != page.rawImagePath) {
                    oldFile.delete()
                }
                
                updatedPages.add(page.copy(filterType = filter.name, processedImagePath = newProcessedPath))
            } finally {
                if (rawBitmap != rotatedRaw) rawBitmap?.recycle()
                if (rotatedRaw != filtered) rotatedRaw?.recycle()
                filtered?.recycle()
            }
        }
        
        repository.updatePages(updatedPages)
        return Result.success()
    }

    private suspend fun handleCropWarp(): Result {
        val pageId = inputData.getLong(KEY_PAGE_ID, -1L)
        if (pageId == -1L) return Result.failure()
        
        val quadJson = inputData.getString(KEY_QUAD_JSON) ?: return Result.failure()
        val quad = DocumentQuad.fromJson(quadJson)
        
        val page = repository.getPageById(pageId) ?: return Result.failure()
        
        var rawBmp: Bitmap? = null
        var warped: Bitmap? = null
        var rotated: Bitmap? = null
        var filtered: Bitmap? = null
        
        return try {
            rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return Result.failure()
            warped = ImageProcessor.applyPerspectiveWarp(rawBmp, quad)
            rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
            val filter = try { FilterType.valueOf(page.filterType) } catch (e: Exception) { FilterType.MAGIC }
            filtered = ImageProcessor.applyFilter(rotated, filter)
            val newPath = ImageProcessor.saveBitmapToFile(applicationContext, filtered, "crop_")
            
            val updatedPage = page.copy(
                cropQuadJson = quad.toJson(),
                processedImagePath = newPath
            )
            repository.updatePage(updatedPage)
            Result.success()
        } finally {
            if (rawBmp != warped) rawBmp?.recycle()
            if (warped != rotated) warped?.recycle()
            if (rotated != filtered) rotated?.recycle()
            filtered?.recycle()
        }
    }

    private suspend fun handleRotate(): Result {
        val pageId = inputData.getLong(KEY_PAGE_ID, -1L)
        if (pageId == -1L) return Result.failure()
        
        val degrees = inputData.getInt(KEY_ROTATION_DEGREES, 90)
        val page = repository.getPageById(pageId) ?: return Result.failure()
        
        var rawBmp: Bitmap? = null
        var warped: Bitmap? = null
        var rotated: Bitmap? = null
        var filtered: Bitmap? = null
        
        return try {
            rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return Result.failure()
            val quad = DocumentQuad.fromJson(page.cropQuadJson)
            warped = ImageProcessor.applyPerspectiveWarp(rawBmp, quad)
            
            val newRotation = (page.rotationDegrees + degrees) % 360
            rotated = ImageProcessor.rotateBitmap(warped, newRotation)
            
            val filter = try { FilterType.valueOf(page.filterType) } catch (e: Exception) { FilterType.MAGIC }
            filtered = ImageProcessor.applyFilter(rotated, filter)
            val newPath = ImageProcessor.saveBitmapToFile(applicationContext, filtered, "rot_")
            
            val updatedPage = page.copy(
                processedImagePath = newPath,
                rotationDegrees = newRotation
            )
            repository.updatePage(updatedPage)
            Result.success()
        } finally {
            if (rawBmp != warped) rawBmp?.recycle()
            if (warped != rotated) warped?.recycle()
            if (rotated != filtered) rotated?.recycle()
            filtered?.recycle()
        }
    }

    companion object {
        const val KEY_OPERATION = "operation"
        const val KEY_URIS = "uris"
        const val KEY_DOC_ID = "doc_id"
        const val KEY_PAGE_ID = "page_id"
        const val KEY_FILTER_TYPE = "filter_type"
        const val KEY_QUAD_JSON = "quad_json"
        const val KEY_ROTATION_DEGREES = "rotation_degrees"
        const val KEY_RESULT_PAGES = "result_pages"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_CONTRAST = "contrast"
        const val KEY_SHARPEN = "sharpen"
        const val KEY_RESULT_PATH = "result_path"
        const val KEY_CLOCKWISE = "clockwise"
        const val KEY_RAW_PATH = "raw_path"
        const val KEY_PROC_PATH = "proc_path"
        const val KEY_IMAGE_PATHS = "image_paths"
        const val KEY_LAYOUT = "layout"
        const val KEY_SCALE = "scale"
        const val KEY_SPACING = "spacing"
        const val KEY_CORNER_RADIUS = "corner_radius"
        const val KEY_HAS_BORDER = "has_border"
        const val KEY_BG_COLOR = "bg_color"
        const val KEY_FIT_MODE = "fit_mode"
        const val KEY_URI = "uri"
        const val KEY_PREFIX = "prefix"

        const val OP_IMPORT = "op_import"
        const val OP_FILTER_ALL = "op_filter_all"
        const val OP_CROP_WARP = "op_crop_warp"
        const val OP_ROTATE = "op_rotate"
        const val OP_EDIT_PROCESS = "op_edit_process"
        const val OP_ROTATE_SESSION = "op_rotate_session"
        const val OP_ROTATE_PENDING = "op_rotate_pending"
        const val OP_MERGE_GRID = "op_merge_grid"
        const val OP_SAVE_BITMAP = "op_save_bitmap"
        const val OP_DETECT_QUAD = "op_detect_quad"
        const val OP_ROTATE_PAGE = "op_rotate_page"
        const val OP_BURN_ANNOTATIONS = "op_burn_annotations"
        const val OP_SAVE_TO_GALLERY = "op_save_to_gallery"
        const val OP_SAVE_DOC_TO_GALLERY = "op_save_doc_to_gallery"
    }
}
