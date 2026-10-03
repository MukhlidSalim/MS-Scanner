package com.example.engine.pdf

import android.content.Context
import android.net.Uri
import com.example.data.db.DocScanDatabase
import com.example.data.model.DocumentCategory
import com.example.data.model.FilterType
import com.example.data.model.PageEntity
import com.example.data.repository.DocumentRepository
import com.example.data.repository.DocumentSaveRequest
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.DocumentAnalysisWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns an opened PDF into a regular MS Scanner document in ONE step, through the existing single save path
 * (DocumentRepository.saveDocument) — used by "Sign" in read-only mode, where the user signs a page with the
 * existing annotation tools. Pages are rendered with the existing DocumentPipeline.importPdf (confirmed
 * function: each page rasterised, quad = full page, status SKIPPED, filter null/ORIGINAL — no edge detection,
 * no filter, no deskew, matching how every other PDF import in the app is handled).
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
            // DocumentPipeline.importPdf takes a Uri; a file:// Uri of our own cache file is readable
            // through ContentResolver.openFileDescriptor without needing a content:// grant.
            val processedPages = DocumentPipeline.importPdf(app, Uri.fromFile(pdfFile), filter = null)
            if (processedPages.isEmpty()) return@withContext null
            val fullQuad = DocumentQuad.fullQuad()
            val entities = processedPages.mapIndexed { i, page ->
                // importPdf already persists the quad/status itself; re-asserting here is defensive and
                // harmless (same values), kept so this importer does not silently depend on that detail.
                QuadStore.save(page.rawPath, fullQuad)
                PageEntity(
                    documentId = 0L,
                    pageIndex = i,
                    rawImagePath = page.rawPath,
                    processedImagePath = page.processedPath,
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
