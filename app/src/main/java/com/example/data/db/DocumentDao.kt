package com.example.data.db

import androidx.room.*
import com.example.data.model.DocumentEntity
import com.example.data.model.PageEntity
import com.example.data.model.SignatureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE isTrash = 0 ORDER BY updatedAt DESC")
    fun getAllActiveDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isTrash = 0 AND isFavorite = 1 ORDER BY updatedAt DESC")
    fun getFavoriteDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isTrash = 1 ORDER BY updatedAt DESC")
    fun getTrashDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isTrash = 1")
    suspend fun getTrashDocumentsList(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE isTrash = 0 AND folderName = :folder ORDER BY updatedAt DESC")
    fun getDocumentsByFolder(folder: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isTrash = 0 AND category = :category ORDER BY updatedAt DESC")
    fun getDocumentsByCategory(category: String): Flow<List<DocumentEntity>>

    @Query("""
        SELECT DISTINCT d.* FROM documents d 
        LEFT JOIN pages p ON p.documentId = d.id 
        WHERE d.isTrash = 0 AND (
            d.title LIKE '%' || :query || '%' 
            OR d.tagsCsv LIKE '%' || :query || '%' 
            OR p.ocrText LIKE '%' || :query || '%'
        )
        ORDER BY d.updatedAt DESC
    """)
    fun searchDocuments(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT DISTINCT folderName FROM documents WHERE isTrash = 0")
    fun getAllFolders(): Flow<List<String>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocumentById(id: Long): Flow<DocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("UPDATE documents SET isTrash = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET isTrash = 0, updatedAt = :timestamp WHERE id = :id")
    suspend fun restoreFromTrash(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun permanentDeleteDocument(id: Long)

    @Query("DELETE FROM documents WHERE isTrash = 1")
    suspend fun emptyTrash()

    // --- Pages ---

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex ASC")
    fun getPagesForDocument(docId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex ASC")
    suspend fun getPagesListForDocument(docId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageById(pageId: Long): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePageById(pageId: Long)

    @Query("DELETE FROM pages WHERE documentId = :docId")
    suspend fun deletePagesForDocument(docId: Long)

    // --- Signatures ---

    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    fun getAllSignatures(): Flow<List<SignatureEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignature(sig: SignatureEntity): Long

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun deleteSignature(id: Long)
}
