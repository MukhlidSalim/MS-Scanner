import re

with open('app/src/main/java/com/example/data/repository/DocumentRepository.kt', 'r', encoding='utf-8') as f:
    content = f.read()

new_stats = """    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
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
    }"""

content = re.sub(r'suspend fun getStorageStats\(\): StorageStats = withContext\(Dispatchers.IO\) \{.*?\)\n    \}', new_stats, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/data/repository/DocumentRepository.kt', 'w', encoding='utf-8') as f:
    f.write(content)
