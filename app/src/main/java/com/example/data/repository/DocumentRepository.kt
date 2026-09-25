package com.example.data.repository

import android.content.Context
import com.example.data.db.DocumentDao
import com.example.data.model.DocumentEntity
import com.example.data.model.PageEntity
import com.example.data.model.SignatureEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

class DocumentRepository(
    private val context: Context,
    private val documentDao: DocumentDao
) {

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

    suspend fun getDocumentById(id: Long): DocumentEntity? = documentDao.getDocumentById(id)
    suspend fun getPagesList(docId: Long): List<PageEntity> = documentDao.getPagesListForDocument(docId)

    /**
     * Transactional creation of new Document with pages
     */
    suspend fun createDocumentWithPages(
        title: String,
        folderName: String = "Default",
        category: String = "OTHER",
        pages: List<Pair<String, String>> // (rawImagePath, processedImagePath)
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val doc = DocumentEntity(
            title = title.ifBlank { "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(now))}" },
            folderName = folderName,
            category = category,
            createdAt = now,
            updatedAt = now,
            pageCount = pages.size,
            thumbnailPath = pages.firstOrNull()?.second ?: pages.firstOrNull()?.first ?: ""
        )
        val docId = documentDao.insertDocument(doc)

        val pageEntities = pages.mapIndexed { index, pair ->
            PageEntity(
                documentId = docId,
                pageIndex = index,
                rawImagePath = pair.first,
                processedImagePath = pair.second,
                createdAt = now
            )
        }
        documentDao.insertPages(pageEntities)
        docId
    }

    suspend fun addPageToDocument(docId: Long, rawPath: String, processedPath: String): Long = withContext(Dispatchers.IO) {
        val existingPages = documentDao.getPagesListForDocument(docId)
        val newIndex = existingPages.size
        val page = PageEntity(
            documentId = docId,
            pageIndex = newIndex,
            rawImagePath = rawPath,
            processedImagePath = processedPath
        )
        val pageId = documentDao.insertPage(page)

        val doc = documentDao.getDocumentById(docId)
        if (doc != null) {
            documentDao.updateDocument(
                doc.copy(
                    pageCount = existingPages.size + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        pageId
    }

    
    suspend fun renameFolder(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        documentDao.renameFolder(oldName, newName)
    }

    suspend fun deleteFolder(folderName: String) = withContext(Dispatchers.IO) {
        documentDao.deleteFolder(folderName)
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
        documentDao.deletePageById(pageId)
        val remaining = documentDao.getPagesListForDocument(docId)
        val doc = documentDao.getDocumentById(docId)
        if (doc != null) {
            // Re-index remaining pages
            remaining.forEachIndexed { idx, p ->
                if (p.pageIndex != idx) {
                    documentDao.updatePage(p.copy(pageIndex = idx))
                }
            }
            documentDao.updateDocument(
                doc.copy(
                    pageCount = remaining.size,
                    thumbnailPath = remaining.firstOrNull()?.processedImagePath ?: "",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun reorderPages(docId: Long, reorderedPages: List<PageEntity>) = withContext(Dispatchers.IO) {
        reorderedPages.forEachIndexed { idx, p ->
            documentDao.updatePage(p.copy(pageIndex = idx))
        }
        val doc = documentDao.getDocumentById(docId)
        if (doc != null) {
            documentDao.updateDocument(
                doc.copy(
                    thumbnailPath = reorderedPages.firstOrNull()?.processedImagePath ?: doc.thumbnailPath,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
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

    suspend fun deleteDocumentPermanently(id: Long) = withContext(Dispatchers.IO) {
        val pages = documentDao.getPagesListForDocument(id)
        pages.forEach { p ->
            try { File(p.rawImagePath).delete() } catch (_: Exception) {}
            try { File(p.processedImagePath).delete() } catch (_: Exception) {}
        }
        documentDao.deletePagesForDocument(id)
        documentDao.permanentDeleteDocument(id)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trashDocs = documentDao.getTrashDocumentsList()
        trashDocs.forEach { doc ->
            val pages = documentDao.getPagesListForDocument(doc.id)
            pages.forEach { p ->
                try { File(p.rawImagePath).delete() } catch (_: Exception) {}
                try { File(p.processedImagePath).delete() } catch (_: Exception) {}
            }
            documentDao.deletePagesForDocument(doc.id)
        }
        documentDao.emptyTrash()
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
