import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

replacement_active = """    fun applyFilterToActivePage(filter: FilterType) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx !in pages.indices) return

        val page = pages[idx]
        viewModelScope.launch {
            var rawBmp: android.graphics.Bitmap? = null
            var warped: android.graphics.Bitmap? = null
            var rotated: android.graphics.Bitmap? = null
            var filtered: android.graphics.Bitmap? = null
            try {
                rawBmp = ImageProcessor.loadBitmapFromFile(page.rawImagePath) ?: return@launch
                val quad = com.example.data.model.DocumentQuad.fromJson(page.cropQuadJson)
                warped = ImageProcessor.warpPerspective(rawBmp, quad)
                rotated = ImageProcessor.rotateBitmap(warped, page.rotationDegrees)
                filtered = ImageProcessor.applyFilter(rotated, filter)
                val newProcessedPath = ImageProcessor.saveBitmapToFile(context, filtered, "flt_")
                
                qualityCache.remove(page.id)
                val updated = page.copy(
                    processedImagePath = newProcessedPath,
                    filterType = filter.name
                )
                repository.updatePage(updated)
            } finally {
                if (rawBmp != warped) rawBmp?.recycle()
                if (warped != rotated) warped?.recycle()
                if (rotated != filtered) rotated?.recycle()
                filtered?.recycle()
            }
        }
    }"""

content = re.sub(r'fun applyFilterToActivePage\(filter: FilterType\) \{.*?\n        \}\n    \}', replacement_active, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
