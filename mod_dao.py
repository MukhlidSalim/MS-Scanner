import re

with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'r', encoding='utf-8') as f:
    content = f.read()

new_methods = """
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun getAllDocumentsSync(): List<DocumentEntity>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex ASC")
    fun getPagesForDocumentSync(docId: Long): List<PageEntity>
"""

content = content.replace('interface DocumentDao {', 'interface DocumentDao {' + new_methods)

with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'w', encoding='utf-8') as f:
    f.write(content)
