package com.example.engine.pdf

import android.content.Context
import com.example.data.db.DocScanDatabase
import com.example.data.model.DocumentCategory
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.repository.DocumentRepository
import com.example.data.repository.DocumentSaveRequest
import com.example.engine.cv.DetectionStatus
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.DocumentAnalysisWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns an opened PDF into a regular MS Scanner document in ONE step, through the existing single save path
 * (DocumentRepository.saveDocument) — used by "Sign" in read-only mode, where the user signs a page with the
 * existing annotation tools. Pages are rendered with the existing PdfEngine.convertPdfToPages and marked as
 * already processed (status SKIPPED, filter ORIGINAL, full-page quad): no edge detection, no filter, no deskew.
 */
object ExternalPdfImporter {
    private const val MAX_PAGES = 200

    /** Clean title: drops the "<timestamp>_" prefix added by PdfEngine.copyUriToLocalPdf. */
    fun titleOf(file: File): String =
        file.nameWithoutExtension.replace(Regex("^\\d{10,}_"), "").ifBlank { "PDF" }

    /** Returns the new document id, or null when the PDF has no readable page (encrypted / damaged). */
    suspend fun importAsDocument(context: Context, pdfFile: File, folderName: String = "Default"): Long? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val pages = PdfEngine.convertPdfToPages(app, pdfFile, MAX_PAGES)
            if (pages.isEmpty()) return@withContext null
            val fullQuad = DocumentQuad.fullQuad()
            val entities = pages.mapIndexed { i, (raw, proc) ->
                QuadStore.save(raw, fullQuad)
                QuadStore.saveStatus(raw, DetectionStatus.SKIPPED)
                PageEntity(
                    documentId = 0L,
                    pageIndex = i,
                    rawImagePath = raw,
                    processedImagePath = proc,
                    filterType = FilterType.ORIGINAL.name,
                    cropQuadJson = fullQuad.toJson()
                )
            }
            val repository = DocumentRepository(app, DocScanDatabase.getInstance(app).documentDao())
            val docId = repository.saveDocument(
                DocumentSaveRequest(
                    existingDocId = null,
                    title = titleOf(pdfFile),
                    folderName = folderName,
                    category = DocumentCategory.OTHER.name,
                    pages = entities
                )
            )
            // OCR / searchable layer in the background, like every other new document.
            runCatching { DocumentAnalysisWorker.enqueue(app, docId, generatedTitle = false) }
            docId
        }
}
