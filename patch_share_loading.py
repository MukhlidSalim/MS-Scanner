import os

filepath = 'app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add isSharingMultiple state
state_old = "var showAddPageDialog by remember { mutableStateOf(false) }"
state_new = "var showAddPageDialog by remember { mutableStateOf(false) }\n    var isSharingMultiple by remember { mutableStateOf(false) }"
content = content.replace(state_old, state_new)

# 2. Modify shareMultipleFiles to accept the state lambda
share_func_old = """private fun shareMultipleFiles(context: Context, files: List<File>, scope: kotlinx.coroutines.CoroutineScope) {
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
share_func_new = """private fun shareMultipleFiles(context: Context, files: List<File>, scope: kotlinx.coroutines.CoroutineScope, onLoading: (Boolean) -> Unit) {
    scope.launch {
        try {
            if (files.isEmpty()) return@launch
            onLoading(true)
            val paths = files.map { it.absolutePath }
            val pdfFile = com.example.engine.cv.ImageProcessor.mergeToPdf(context, paths)
            shareFile(context, pdfFile, "application/pdf")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            onLoading(false)
        }
    }
}"""
content = content.replace(share_func_old, share_func_new)

# 3. Update all 3 usages
content = content.replace(
    'shareMultipleFiles(context, selectedFiles, coroutineScope)',
    'shareMultipleFiles(context, selectedFiles, coroutineScope) { isSharingMultiple = it }'
)
content = content.replace(
    'shareMultipleFiles(context, allFiles, coroutineScope)',
    'shareMultipleFiles(context, allFiles, coroutineScope) { isSharingMultiple = it }'
)

# 4. Add the loading overlay at the end of the Scaffold
overlay_ui = """
    if (isSharingMultiple) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Generating PDF...", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
"""

content = content.replace('    if (showReorderSheet) {', overlay_ui + '\n    if (showReorderSheet) {')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
