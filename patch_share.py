import os

filepath = 'app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Modify shareMultipleFiles callers
content = content.replace('shareMultipleFiles(context, selectedFiles)', 'shareMultipleFiles(context, selectedFiles, coroutineScope)')
content = content.replace('shareMultipleFiles(context, allFiles)', 'shareMultipleFiles(context, allFiles, coroutineScope)')

# Replace shareMultipleFiles definition
old_def = """private fun shareMultipleFiles(context: Context, files: List<File>, mimeType: String = "image/jpeg") {
    try {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Pages via"))
    } catch (e: Exception) {}
}"""

new_def = """private fun shareMultipleFiles(context: Context, files: List<File>, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        try {
            val bitmaps = files.mapNotNull { file ->
                com.example.engine.cv.ImageProcessor.loadBitmapFromFile(file.absolutePath)
            }
            if (bitmaps.isEmpty()) return@launch
            
            val pdfFile = com.example.engine.cv.ImageProcessor.mergeToPdf(context, bitmaps)
            shareFile(context, pdfFile, "application/pdf")
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}"""

content = content.replace(old_def, new_def)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
