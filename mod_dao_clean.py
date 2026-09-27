with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("@Query(\"UPDATE documents SET folderName = 'ALL' WHERE folderName = :folderName\")\n", "")

with open('app/src/main/java/com/example/data/db/DocumentDao.kt', 'w', encoding='utf-8') as f:
    f.write(content)
