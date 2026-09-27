with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('suspend fun renameFolder(oldName: String, newName: String)', '@Query("UPDATE documents SET folderName = :newName WHERE folderName = :oldName")\n    suspend fun renameFolder(oldName: String, newName: String)')
content = content.replace('suspend fun deleteFolder(folderName: String)', '@Query("UPDATE documents SET folderName = \'Default\' WHERE folderName = :folderName")\n    suspend fun deleteFolder(folderName: String)')
content = content.replace('suspend fun updatePages(pages: List<PageEntity>)', '@Update\n    suspend fun updatePages(pages: List<PageEntity>)')

with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'w', encoding='utf-8') as f:
    f.write(content)
