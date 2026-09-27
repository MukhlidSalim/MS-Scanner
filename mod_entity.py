with open('app/src/main/java/com/example/data/model/DocumentEntity.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('val fileSizeFormatted: String = ""', 'val fileSizeFormatted: String = "",\n    val sizeBytes: Long = 0L')

with open('app/src/main/java/com/example/data/model/DocumentEntity.kt', 'w', encoding='utf-8') as f:
    f.write(content)
