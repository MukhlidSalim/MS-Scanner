import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

replacement_all = """    fun applyFilterToAllPages(context: Context, filter: FilterType) {
        viewModelScope.launch {
            try {
                val docId = _uiState.value.activeDocument?.id ?: return@launch
                val pages = repository.getPagesList(docId)
                if (pages.isEmpty()) return@launch

                val updatedPages = pages.map { page ->
                    var rawBitmap: android.graphics.Bitmap? = null
                    var rotatedRaw: android.graphics.Bitmap? = null
                    var filtered: android.graphics.Bitmap? = null
                    var newProcessedPath: String = page.processedImagePath
                    
                    try {
                        rawBitmap = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@map page
                        rotatedRaw = ImageProcessor.rotateBitmap(rawBitmap, page.rotationDegrees)
                        filtered = ImageProcessor.applyFilter(rotatedRaw, filter)
                        newProcessedPath = ImageProcessor.saveBitmapToFile(context, filtered, "proc_all_")
                        
                        val oldFile = java.io.File(page.processedImagePath)
                        if (oldFile.exists() && oldFile.absolutePath != page.rawImagePath) {
                            oldFile.delete()
                        }
                    } finally {
                        if (rawBitmap != rotatedRaw) rawBitmap?.recycle()
                        if (rotatedRaw != filtered) rotatedRaw?.recycle()
                        filtered?.recycle()
                    }
                    
                    page.copy(filterType = filter.name, processedImagePath = newProcessedPath)
                }

                repository.updatePages(updatedPages)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }"""

content = re.sub(r'fun applyFilterToAllPages\(context: Context, filter: FilterType\) \{.*?\n        \}\n    \}', replacement_all, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
