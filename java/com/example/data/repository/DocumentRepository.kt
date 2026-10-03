package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.db.DocScanDatabase
import com.example.data.db.DocumentDao
import com.example.data.model.DocumentEntity
import com.example.data.model.FavoriteFolderEntity
import com.example.data.model.PageEntity
import com.example.data.model.SignatureEntity
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.OcrLayoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class StorageStats(
    val totalDocumentsCount: Int,
    val totalPagesCount: Int,
    val scansSizeBytes: Long,
    val cacheSizeBytes: Long,
    val trashCount: Int
)

/**
 * Input of the single save transaction.
 * [pages] is the FINAL ordered page list: id == 0 -> inserted, id > 0 -> updated (documentId and
 * pageIndex are always rewritten from the list order). Pages of the document that are not in the list
 * are deleted (their ids must be listed in [removedPageIds] for safety).
 */
data class DocumentSaveRequest(
    val existingDocId: Long?,
    val title: String,
    val folderName: String = "Default",
    val category: String? = null,
    val pages: List<PageEntity>,
    val removedPageIds: List<Long> = emptyList()
)

class DocumentRepository(
    private val context: Context,
    private val documentDao: DocumentDao
) {
    private val database by lazy { DocScanDatabase.getInstance(context.applicationContext) }
    /**
     * User-created folders. Stored in the existing `favorite_folders` table (same shape: id, folderName,
     * createdAt) so an EMPTY folder exists and appears before any document is saved in it — no migration.
     */
    private val folderDao by lazy { database.favoriteFolderDao() }

    suspend fun getAllDocumentsSync(): List<DocumentEntity> = withContext(Dispatchers.IO) {
        documentDao.getAllDocumentsSync()
    }

    suspend fun getPagesForDocumentSync(docId: Long): List<PageEntity> = withContext(Dispatchers.IO) {
        documentDao.getPagesForDocumentSync(docId)
    }

    suspend fun insertDocumentForRestore(doc: DocumentEntity): Long = withContext(Dispatchers.IO) {
        documentDao.insertDocument(doc)
    }

    fun getAllDocuments(): Flow<List<DocumentEntity>> = documentDao.getAllActiveDocuments()
    fun getFavoriteDocuments(): Flow<List<DocumentEntity>> = documentDao.getFavoriteDocuments()
    fun getTrashDocuments(): Flow<List<DocumentEntity>> = documentDao.getTrashDocuments()
    fun getDocumentsByFolder(folder: String): Flow<List<DocumentEntity>> = documentDao.getDocumentsByFolder(folder)
    fun getDocumentsByCategory(category: String): Flow<List<DocumentEntity>> = documentDao.getDocumentsByCategory(category)
    fun searchDocuments(query: String): Flow<List<DocumentEntity>> = documentDao.searchDocuments(query)
    fun getAllFolders(): Flow<List<String>> = documentDao.getAllFolders()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = documentDao.observeDocumentById(id)
    fun getPagesForDocument(docId: Long): Flow<List<PageEntity>> = documentDao.getPagesForDocument(docId)
    fun getAllSignatures(): Flow<List<SignatureEntity>> = documentDao.getAllSignatures()

    suspend fun getDocumentById(id: Long): DocumentEntity? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(id)
    }

    suspend fun getPageById(id: Long): PageEntity? = withContext(Dispatchers.IO) {
        documentDao.getPageById(id)
    }

    suspend fun getPagesList(docId: Long): List<PageEntity> = withContext(Dispatchers.IO) {
        documentDao.getPagesListForDocument(docId)
    }

    /**
     * THE single save path for new scans, imports, "add pages to document" and edit sessions.
     *
     * - The thumbnail is rendered BEFORE the transaction (file I/O never inside a DB transaction).
     * - Document + every page are written in ONE Room transaction: either all pages are saved with
     *   their final order, crop quad, rotation and filter, or nothing is (no partial / lost pages).
     * - The previous thumbnail is deleted only after a successful commit.
     * Returns the document id.
     */
    suspend fun saveDocument(request: DocumentSaveRequest): Long = withContext(Dispatchers.IO) {
        require(request.pages.isNotEmpty()) { "A document must contain at least one page" }
        val now = System.currentTimeMillis()
        val firstPath = request.pages.first().processedImagePath.ifBlank { request.pages.first().rawImagePath }
        val newThumb = com.example.engine.cv.ImageProcessor.createThumbnail(context, firstPath)
        var oldThumb: String? = null
        try {
            val docId = database.withTransaction {
                val existing = request.existingDocId?.takeIf { it > 0L }?.let { documentDao.getDocumentById(it) }
                if (request.existingDocId != null && request.existingDocId > 0L && existing == null) {
                    throw IllegalStateException("Document ${request.existingDocId} no longer exists")
                }
                val id = if (existing == null) {
                    documentDao.insertDocument(
                        DocumentEntity(
                            title = request.title.ifBlank {
                                "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(now))}"
                            },
                            folderName = request.folderName,
                            category = request.category ?: com.example.data.model.DocumentCategory.OTHER.name,
                            createdAt = now,
                            updatedAt = now,
                            pageCount = request.pages.size,
                            thumbnailPath = newThumb
                        )
                    )
                } else {
                    oldThumb = existing.thumbnailPath
                    existing.id
                }
                request.removedPageIds.forEach { documentDao.deletePageById(it) }
                val savedIds = HashSet<Long>()
                request.pages.forEachIndexed { index, page ->
                    val p = page.copy(documentId = id, pageIndex = index)
                    if (p.id == 0L) {
                        savedIds += documentDao.insertPage(p)
                    } else {
                        documentDao.updatePage(p)
                        savedIds += p.id
                    }
                }
                // Safety net: the final list IS the document; no orphan page can survive the save.
                documentDao.getPagesListForDocument(id)
                    .filter { it.id !in savedIds }
                    .forEach { documentDao.deletePageById(it.id) }
                val finalCount = request.pages.size
                if (existing != null) {
                    documentDao.updateDocument(
                        existing.copy(
                            title = request.title.ifBlank { existing.title },
                            category = request.category ?: existing.category,
                            pageCount = finalCount,
                            thumbnailPath = newThumb ?: existing.thumbnailPath,
                            updatedAt = now
                        )
                    )
                }
                id
            }
            if (newThumb != null && oldThumb != null && oldThumb != newThumb) {
                runCatching { File(oldThumb!!).delete() }
            }
            docId
        } catch (e: Throwable) {
            newThumb?.let { runCatching { File(it).delete() } }
            throw e
        }
    }

    /**
     * Legacy entry point kept for existing callers: delegates to [saveDocument] (one save path).
     */
    suspend fun createDocumentWithPages(
        title: String,
        folderName: String = "Default",
        category: String = "OTHER",
        ocrText: String = "",
        pages: List<Pair<String, String>> // (rawImagePath, processedImagePath)
    ): Long {
        val now = System.currentTimeMillis()
        val id = saveDocument(
            DocumentSaveRequest(
                existingDocId = null,
                title = title,
                folderName = folderName,
                category = category,
                pages = pages.mapIndexed { index, pair ->
                    PageEntity(
                        documentId = 0L,
                        pageIndex = index,
                        rawImagePath = pair.first,
                        processedImagePath = pair.second,
                        createdAt = now
                    )
                }
            )
        )
        if (ocrText.isNotBlank()) {
            getDocumentById(id)?.let { updateDocument(it.copy(ocrText = ocrText)) }
        }
        return id
    }

    suspend fun addPageToDocument(docId: Long, rawPath: String, processedPath: String): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existingPages = documentDao.getPagesListForDocument(docId)
            val pageId = documentDao.insertPage(
                PageEntity(
                    documentId = docId,
                    pageIndex = existingPages.size,
                    rawImagePath = rawPath,
                    processedImagePath = processedPath
                )
            )
            documentDao.getDocumentById(docId)?.let { doc ->
                documentDao.updateDocument(doc.copy(pageCount = existingPages.size + 1, updatedAt = System.currentTimeMillis()))
            }
            pageId
        }
    }

    /** Folders from documents + user-created (possibly empty) folders; "Default" and "ALL" excluded. */
    fun observeFolders(): Flow<List<String>> =
        combine(documentDao.getAllFolders(), folderDao.getAllFavoriteFolders()) { docFolders, created ->
            (docFolders + created.map { it.folderName })
                .map { it.trim() }
                .filter { it.isNotBlank() && !isReservedFolder(it) }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
        }

    fun isReservedFolder(name: String): Boolean =
        name.equals("ALL", ignoreCase = true) || name.equals("Default", ignoreCase = true)

    /** Returns false when the name is empty, reserved or already used (case-insensitive). */
    suspend fun createFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 60 || isReservedFolder(clean)) return@withContext false
        val existing = documentDao.getAllFolders().first() + folderDao.getAllFavoriteFolders().first().map { it.folderName }
        if (existing.any { it.equals(clean, ignoreCase = true) }) return@withContext false
        folderDao.insertFolder(FavoriteFolderEntity(folderName = clean))
        true
    }

    suspend fun renameFolder(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isBlank() || isReservedFolder(clean) || clean == oldName) return@withContext
        val rows = folderDao.getAllFavoriteFolders().first().filter { it.folderName == oldName }
        database.withTransaction {
            documentDao.renameFolder(oldName, clean)
            rows.forEach { folderDao.deleteFolder(it) }
            folderDao.insertFolder(FavoriteFolderEntity(folderName = clean))
        }
    }

    /** Documents of the folder go back to "Default" (never deleted); the folder itself is removed. */
    suspend fun deleteFolder(folderName: String) = withContext(Dispatchers.IO) {
        val rows = folderDao.getAllFavoriteFolders().first().filter { it.folderName == folderName }
        database.withTransaction {
            documentDao.deleteFolder(folderName)
            rows.forEach { folderDao.deleteFolder(it) }
        }
    }

    suspend fun moveDocumentsToFolder(docIds: List<Long>, folder: String) = withContext(Dispatchers.IO) {
        val target = folder.trim().ifBlank { "Default" }
        database.withTransaction {
            docIds.forEach { id ->
                documentDao.getDocumentById(id)?.let {
                    documentDao.updateDocument(it.copy(folderName = target, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun updatePages(pages: List<PageEntity>) = withContext(Dispatchers.IO) {
        documentDao.updatePages(pages)
    }

    suspend fun updatePage(page: PageEntity) = withContext(Dispatchers.IO) {
        documentDao.updatePage(page)
        val doc = documentDao.getDocumentById(page.documentId)
        if (doc != null) {
            documentDao.updateDocument(doc.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun updatePagesIndices(pages: List<PageEntity>) = withContext(Dispatchers.IO) {
        documentDao.updatePages(pages)
    }

    suspend fun insertPage(page: PageEntity): Long = withContext(Dispatchers.IO) {
        documentDao.insertPage(page)
    }

    suspend fun deletePage(pageId: Long, docId: Long) = withContext(Dispatchers.IO) {
        val oldThumb = database.withTransaction {
            documentDao.deletePageById(pageId)
            val remaining = documentDao.getPagesListForDocument(docId)
            remaining.forEachIndexed { idx, p ->
                if (p.pageIndex != idx) documentDao.updatePage(p.copy(pageIndex = idx))
            }
            val doc = documentDao.getDocumentById(docId)
            if (doc != null) {
                documentDao.updateDocument(doc.copy(pageCount = remaining.size, updatedAt = System.currentTimeMillis()))
            }
            doc?.thumbnailPath
        }
        // Thumbnail regenerated outside the transaction.
        val first = documentDao.getPagesListForDocument(docId).firstOrNull()
        val newThumb = first?.processedImagePath?.let { com.example.engine.cv.ImageProcessor.createThumbnail(context, it) }
        documentDao.getDocumentById(docId)?.let { documentDao.updateDocument(it.copy(thumbnailPath = newThumb)) }
        oldThumb?.let { if (it != newThumb) runCatching { File(it).delete() } }
    }

    suspend fun reorderPages(docId: Long, reorderedPages: List<PageEntity>) = withContext(Dispatchers.IO) {
        val oldThumb = database.withTransaction {
            reorderedPages.forEachIndexed { idx, p -> documentDao.updatePage(p.copy(pageIndex = idx)) }
            documentDao.getDocumentById(docId)?.thumbnailPath
        }
        val newThumbPath = reorderedPages.firstOrNull()?.processedImagePath?.let {
            com.example.engine.cv.ImageProcessor.createThumbnail(context, it)
        }
        documentDao.getDocumentById(docId)?.let {
            documentDao.updateDocument(it.copy(thumbnailPath = newThumbPath, updatedAt = System.currentTimeMillis()))
        }
        oldThumb?.let { if (it != newThumbPath) runCatching { File(it).delete() } }
    }

    suspend fun updateDocument(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        documentDao.updateDocument(doc.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleFavorite(id: Long) = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(id) ?: return@withContext
        documentDao.updateDocument(doc.copy(isFavorite = !doc.isFavorite, updatedAt = System.currentTimeMillis()))
    }

    suspend fun moveToTrash(id: Long) = withContext(Dispatchers.IO) {
        documentDao.moveToTrash(id)
    }

    suspend fun restoreFromTrash(id: Long) = withContext(Dispatchers.IO) {
        documentDao.restoreFromTrash(id)
    }

    private fun deletePageFiles(p: PageEntity) {
        runCatching { File(p.rawImagePath).delete() }
        runCatching { QuadStore.delete(p.rawImagePath) }
        OcrLayoutStore.delete(p.processedImagePath)
        if (p.processedImagePath != p.rawImagePath) runCatching { File(p.processedImagePath).delete() }
    }

    suspend fun deleteDocumentPermanently(id: Long) = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(id)
        val pages = documentDao.getPagesListForDocument(id)
        database.withTransaction {
            documentDao.deletePagesForDocument(id)
            documentDao.permanentDeleteDocument(id)
        }
        // Files are removed only after the rows are gone (never rows pointing to deleted files).
        doc?.thumbnailPath?.let { runCatching { File(it).delete() } }
        pages.forEach { deletePageFiles(it) }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trashDocs = documentDao.getTrashDocumentsList()
        val pagesToDelete = trashDocs.flatMap { documentDao.getPagesListForDocument(it.id) }
        database.withTransaction {
            trashDocs.forEach { documentDao.deletePagesForDocument(it.id) }
            documentDao.emptyTrash()
        }
        trashDocs.forEach { d -> d.thumbnailPath?.let { runCatching { File(it).delete() } } }
        pagesToDelete.forEach { deletePageFiles(it) }
    }

    suspend fun saveSignature(title: String, path: String): Long = withContext(Dispatchers.IO) {
        documentDao.insertSignature(SignatureEntity(title = title, imagePath = path))
    }

    suspend fun deleteSignature(id: Long) = withContext(Dispatchers.IO) {
        documentDao.deleteSignature(id)
    }

    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        val scansDir = java.io.File(context.filesDir, "scans")
        val cacheDir = context.cacheDir
        val scansSize = getFolderSize(scansDir)
        val cacheSize = getFolderSize(cacheDir)
        val allDocs = documentDao.getAllDocumentsSync()
        val totalDocs = allDocs.size
        val totalPages = allDocs.sumOf { it.pageCount }
        val trashCount = allDocs.count { it.isTrash }
        StorageStats(
            totalDocumentsCount = totalDocs,
            totalPagesCount = totalPages,
            scansSizeBytes = scansSize,
            cacheSizeBytes = cacheSize,
            trashCount = trashCount
        )
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        context.cacheDir.deleteRecursively()
        File(context.filesDir, "exports").deleteRecursively()
    }

    private fun getFolderSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
