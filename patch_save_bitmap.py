import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = """    suspend fun saveBitmapToFile(context: Context, bitmap: Bitmap, prefix: String = "scan_"): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        file.absolutePath
    }"""

new_block = """    suspend fun saveBitmapToFile(
        context: Context, 
        bitmap: Bitmap, 
        prefix: String = "scan_", 
        isTextContent: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        
        // JPEG compression: 90 for text/documents, 75 for photos/raw images
        val quality = if (isTextContent) 90 else 75
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        file.absolutePath
    }"""

content = content.replace(old_block, new_block)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
