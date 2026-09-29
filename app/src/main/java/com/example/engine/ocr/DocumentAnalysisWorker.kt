package com.example.engine.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.db.DocScanDatabase
import com.example.data.model.DocumentCategory
import com.example.data.repository.DocumentRepository
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Background document analysis: OCR for every page that has no text yet, document classification and
 * suggested title. Runs through WorkManager, so it survives navigation, process death and app restarts,
 * and never blocks capture / review / save.
 *
 * One unique job per document (REPLACE): re-saving a document restarts its analysis instead of stacking
 * duplicate jobs. Pages are re-read right before each write, so concurrent user edits are not overwritten.
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

        return try {
            val doc = repository.getDocumentById(docId) ?: return Result.success()
            val pages = repository.getPagesList(docId)
            if (pages.isEmpty()) return Result.success()

            var firstResult: DocumentAnalysisResult? = null
            var failures = 0
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to pages.size))
            for ((index, page) in pages.withIndex()) {
                if (isStopped) return Result.retry()
                setProgress(workDataOf(KEY_DONE to index, KEY_TOTAL to pages.size))
                if (page.ocrText.isNotBlank() && !isFailureText(page.ocrText)) continue
                val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 1600)
                if (bmp == null) {
                    failures++
                    continue
                }
                val result = try {
                    DocumentAiEngine.performOfflineOcr(bmp)
                } finally {
                    bmp.recycle()
                }
                if (isFailure(result)) {
                    failures++
                    continue
                }
                if (index == 0) firstResult = result
                // Re-read right before writing: never overwrite a crop/filter the user just changed.
                repository.getPageById(page.id)?.let { current ->
                    repository.updatePage(current.copy(ocrText = result.fullText))
                }
            }

            val fresh = repository.getPagesList(docId)
            val fullText = fresh.map { it.ocrText }.filter { it.isNotBlank() && !isFailureText(it) }.joinToString("\n").trim()
            val current = repository.getDocumentById(docId) ?: return Result.success()
            var updated = current.copy(ocrText = fullText)
            firstResult?.let { r ->
                if (r.detectedCategory != DocumentCategory.OTHER || current.category.isBlank()) {
                    updated = updated.copy(category = r.detectedCategory.name)
                }
                if (r.suggestedTitle.isNotBlank()) {
                    updated = updated.copy(suggestedTitle = r.suggestedTitle)
                    val titleStillGenerated = current.title.isBlank() || current.title.startsWith("Doc_")
                    if (generatedTitle && titleStillGenerated && !r.suggestedTitle.startsWith("Document - ")) {
                        updated = updated.copy(title = r.suggestedTitle)
                    }
                }
            }
            if (updated != current) repository.updateDocument(updated)

            val output = workDataOf(KEY_DONE to pages.size, KEY_TOTAL to pages.size, KEY_FAILED to failures)
            if (failures > 0 && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success(output)
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
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"

        private fun uniqueName(docId: Long) = "analyze_doc_$docId"

        /** Progress of the background analysis of [docId]: (done, total), or null when idle / finished. */
        fun observeProgress(context: Context, docId: Long): Flow<Pair<Int, Int>?> =
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(uniqueName(docId))
                .map { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                        ?: return@map null
                    val total = running.progress.getInt(KEY_TOTAL, 0)
                    Pair(running.progress.getInt(KEY_DONE, 0), total)
                }

        fun enqueue(context: Context, docId: Long, generatedTitle: Boolean) {
            if (docId <= 0L) return
            val request = OneTimeWorkRequestBuilder<DocumentAnalysisWorker>()
                .setInputData(workDataOf(KEY_DOC_ID to docId, KEY_GENERATED_TITLE to generatedTitle))
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
