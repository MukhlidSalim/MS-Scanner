with open("app/src/main/java/com/example/data/repository/DocumentRepository.kt", "r", encoding="utf-8") as f:
    content = f.read()

new_methods = """
    suspend fun getAllDocumentsSync(): List<DocumentEntity> = withContext(Dispatchers.IO) {
        documentDao.getAllDocumentsSync()
    }
    
    suspend fun getPagesForDocumentSync(docId: Long): List<PageEntity> = withContext(Dispatchers.IO) {
        documentDao.getPagesForDocumentSync(docId)
    }
    
    suspend fun insertDocumentForRestore(doc: DocumentEntity): Long = withContext(Dispatchers.IO) {
        documentDao.insertDocument(doc)
    }
"""

content = content.replace("fun getAllDocuments(): Flow<List<DocumentEntity>> = documentDao.getAllActiveDocuments()", new_methods + "\n    fun getAllDocuments(): Flow<List<DocumentEntity>> = documentDao.getAllActiveDocuments()")

with open("app/src/main/java/com/example/data/repository/DocumentRepository.kt", "w", encoding="utf-8") as f:
    f.write(content)
