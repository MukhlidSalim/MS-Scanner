import re

with open('app/src/main/java/com/example/data/repository/BackupManager.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = re.sub(r'\s*put\("thumbnailPath", if \(page\.thumbnailPath\.isNotBlank\(\)\) File\(page\.thumbnailPath\)\.name else ""\)', '', content)
content = re.sub(r'\s*if \(page\.thumbnailPath\.isNotBlank\(\)\) \{\n\s*copyFileToZip\(page\.thumbnailPath, "\$\{docDir\}pages/\$\{File\(page\.thumbnailPath\)\.name\}", zos\)\n\s*\}', '', content)
content = re.sub(r'\s*val thumbName = pageObj\.optString\("thumbnailPath", ""\)', '', content)
content = re.sub(r'\s*val newThumbPath = if \(thumbName\.isNotBlank\(\)\) File\(destDir, thumbName\) else null', '', content)
content = re.sub(r'\s*thumbnailPath = newThumbPath\?\.absolutePath \?: "",', '', content)
content = re.sub(r'\s*documentDao\.updateDocumentThumbnail\(newDocId, firstThumb\)', '', content)

with open('app/src/main/java/com/example/data/repository/BackupManager.kt', 'w', encoding='utf-8') as f:
    f.write(content)
