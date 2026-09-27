with open("app/src/main/java/com/example/data/repository/BackupManager.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("import com.example.data.db.DocumentDao", "")
content = content.replace("private val documentDao: DocumentDao", "private val repository: DocumentRepository")

content = content.replace("documentDao.getAllDocumentsSync()", "repository.getAllDocumentsSync()")
content = content.replace("documentDao.getPagesForDocumentSync(doc.id)", "repository.getPagesForDocumentSync(doc.id)")
content = content.replace("documentDao.insertDocument(", "repository.insertDocumentForRestore(")
content = content.replace("documentDao.insertPage(", "repository.insertPage(")

with open("app/src/main/java/com/example/data/repository/BackupManager.kt", "w", encoding="utf-8") as f:
    f.write(content)
