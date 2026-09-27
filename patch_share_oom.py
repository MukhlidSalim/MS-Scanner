import os

filepath = 'app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_share = """private fun shareMultipleFiles(context: Context, files: List<File>, scope: kotlinx.coroutines.CoroutineScope) {
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

new_share = """private fun shareMultipleFiles(context: Context, files: List<File>, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        try {
            if (files.isEmpty()) return@launch
            
            val paths = files.map { it.absolutePath }
            val pdfFile = com.example.engine.cv.ImageProcessor.mergeToPdf(context, paths)
            shareFile(context, pdfFile, "application/pdf")
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}"""

content = content.replace(old_share, new_share)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
