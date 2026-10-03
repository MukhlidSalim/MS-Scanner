package com.example.engine.ocr

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.db.DocScanDatabase
import com.example.data.model.DocumentCategory
import com.example.data.repository.AppPreferences
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Background document analysis: OCR (Arabic + English), positioned text layout for the searchable PDF,
 * classification and suggested title. Runs through WorkManager: survives navigation, process death and
 * app restarts, and never blocks capture / review / save.
 *
 * A page is analysed when its CURRENT image has no layout file yet (OcrLayoutStore). Editing a page
 * (crop, rotate, replace) produces a new image path, so its text is refreshed automatically.
 * One unique job per document (REPLACE). Pages are re-read right before each write.
 */
class DocumentAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getLong(KEY_DOC_ID, 0L)
        if (docId <= 0L) return Result.success()
        val generatedTitle = inputData.getBoolean(KEY_GENERATED_TITLE, false)

        val context = applicationContext
        val repository = DocumentRepository(context, DocScanDatabase.getInstance(context).documentDao())
        val language = runCatching { OcrLanguage.valueOf(AppPreferences(context).ocrLanguage) }.getOrDefault(OcrLanguage.AUTO)

        return try {
            if (repository.getDocumentById(docId) == null) return Result.success()
            val pages = repository.getPagesList(docId)
            if (pages.isEmpty()) return Result.success()

            // Arabic model: bundled asset or one-time download. Missing -> ML Kit now, Arabic on a later retry.
            val arabicNeeded = language != OcrLanguage.ENGLISH
            val arabicReady = !arabicNeeded || TessDataManager.ensureArabic(context)

            var firstResult: DocumentAnalysisResult? = null
            var failures = 0
            var waitingForArabic = false
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to pages.size))
            for ((index, page) in pages.withIndex()) {
                if (isStopped) return Result.retry()
                setProgress(workDataOf(KEY_DONE to index, KEY_TOTAL to pages.size))
                val imagePath = page.processedImagePath
                if (OcrLayoutStore.exists(imagePath) && !isFailureText(page.ocrText)) continue // current image already analysed
                val bmp = ImageProcessor.loadBitmapFromFile(imagePath, 2000)
                if (bmp == null) {
                    failures++
                    continue
                }
                val result = try {
                    DocumentAiEngine.analyze(context, bmp, language)
                } finally {
                    bmp.recycle()
                }
                if (isFailure(result)) {
                    failures++
                    continue
                }
                if (index == 0) firstResult = result
                // Re-read right before writing: never overwrite a crop/filter the user just changed.
                val current = repository.getPageById(page.id) ?: continue
                if (current.processedImagePath != imagePath) continue // edited meanwhile: next run handles it
                repository.updatePage(current.copy(ocrText = result.fullText))
                if (result.engine == "mlkit-fallback" && arabicNeeded) {
                    // Searchable now (Latin), but not final: keep no layout so Arabic is retried later.
                    waitingForArabic = true
                } else {
                    OcrLayoutStore.save(imagePath, result.lines)
                }
            }

            val fresh = repository.getPagesList(docId)
            val fullText = fresh.map { it.ocrText }.filter { it.isNotBlank() && !isFailureText(it) }.joinToString("\n").trim()
            val doc = repository.getDocumentById(docId) ?: return Result.success()
            var updated = doc.copy(ocrText = fullText)
            firstResult?.let { r ->
                if (r.detectedCategory != DocumentCategory.OTHER &&
                    (doc.category.isBlank() || doc.category == DocumentCategory.OTHER.name)
                ) {
                    updated = updated.copy(category = r.detectedCategory.name)
                }
                if (r.suggestedTitle.isNotBlank()) {
                    updated = updated.copy(suggestedTitle = r.suggestedTitle)
                    val titleStillGenerated = doc.title.isBlank() || doc.title.startsWith("Doc_")
                    if (generatedTitle && titleStillGenerated && !r.suggestedTitle.startsWith("Document - ")) {
                        updated = updated.copy(title = r.suggestedTitle)
                    }
                }
            }
            if (updated != doc) repository.updateDocument(updated)

            val output = workDataOf(KEY_DONE to pages.size, KEY_TOTAL to pages.size, KEY_FAILED to failures)
            when {
                waitingForArabic || (arabicNeeded && !arabicReady) ->
                    if (runAttemptCount < MAX_MODEL_RETRIES) Result.retry() else Result.success(output)
                failures > 0 && runAttemptCount < MAX_RETRIES -> Result.retry()
                else -> Result.success(output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.printStackTrace()
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private fun isFailureText(text: String) = text.startsWith("OCR Failed")

    private fun isFailure(result: DocumentAnalysisResult) =
        result.confidence <= 0f && isFailureText(result.fullText)

    companion object {
        private const val KEY_DOC_ID = "doc_id"
        private const val KEY_GENERATED_TITLE = "generated_title"
        private const val MAX_RETRIES = 2
        private const val MAX_MODEL_RETRIES = 8
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"

        private fun uniqueName(docId: Long) = "analyze_doc_$docId"

        /** Progress of the background analysis of [docId]: (done, total), or null when idle / finished. */
        fun observeProgress(context: Context, docId: Long): Flow<Pair<Int, Int>?> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(uniqueName(docId))
                .map { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?: return@map null
                    val total = running.progress.getInt(KEY_TOTAL, 0)
                    Pair(running.progress.getInt(KEY_DONE, 0), total)
                }

        fun enqueue(context: Context, docId: Long, generatedTitle: Boolean) {
            if (docId <= 0L) return
            val request = OneTimeWorkRequestBuilder<DocumentAnalysisWorker>()
                .setInputData(workDataOf(KEY_DOC_ID to docId, KEY_GENERATED_TITLE to generatedTitle))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("document_analysis")
                .build()
            try {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(uniqueName(docId), ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun cancel(context: Context, docId: Long) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(docId)) }
        }
    }
}
