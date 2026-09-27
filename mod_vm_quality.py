import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add qualityCache
content = content.replace(
    'private val filterTrigger = MutableStateFlow(FilterState())',
    'private val filterTrigger = MutableStateFlow(FilterState())\n    private val qualityCache = mutableMapOf<Long, QualityReport>()'
)

# 2. Modify selectPageIndex
new_select_page = """    fun selectPageIndex(index: Int) {
        val pages = _uiState.value.activePages
        if (index in pages.indices) {
            _uiState.update { it.copy(selectedPageIndex = index) }
            viewModelScope.launch {
                val page = pages[index]
                if (qualityCache.containsKey(page.id)) {
                    _uiState.update { it.copy(currentQualityReport = qualityCache[page.id]) }
                } else {
                    val bmp = ImageProcessor.loadBitmapFromFile(page.processedImagePath, 500)
                    if (bmp != null) {
                        val report = ImageProcessor.analyzeQuality(bmp)
                        qualityCache[page.id] = report
                        _uiState.update { it.copy(currentQualityReport = report) }
                        bmp.recycle()
                    }
                }
            }
        }
    }"""
content = re.sub(r'fun selectPageIndex\(index: Int\) \{.*?\}', new_select_page, content, flags=re.DOTALL)

# 3. Modify loadDocument (same logic for first page)
new_load_first_page = """                if (firstPage != null) {
                    if (qualityCache.containsKey(firstPage.id)) {
                        _uiState.update { it.copy(currentQualityReport = qualityCache[firstPage.id]) }
                    } else {
                        val bmp = ImageProcessor.loadBitmapFromFile(firstPage.processedImagePath, 500)
                        if (bmp != null) {
                            val report = ImageProcessor.analyzeQuality(bmp)
                            qualityCache[firstPage.id] = report
                            _uiState.update { it.copy(currentQualityReport = report) }
                            bmp.recycle()
                        }
                    }
                }"""
content = re.sub(r'if \(firstPage != null\) \{.*?\}\n                \}', new_load_first_page, content, flags=re.DOTALL)

# 4. Invalidate cache on filter/edit
content = content.replace('val updated = page.copy(', 'qualityCache.remove(page.id)\n            val updated = page.copy(')

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
