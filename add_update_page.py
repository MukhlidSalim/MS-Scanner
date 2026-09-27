import re
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

func = """    fun updateActivePageProcessedImage(newPath: String) {
        val pages = _uiState.value.activePages
        val idx = _uiState.value.selectedPageIndex
        if (idx in pages.indices) {
            val page = pages[idx]
            viewModelScope.launch {
                val updatedPage = page.copy(processedImagePath = newPath)
                repository.updatePage(updatedPage)
                val newList = pages.toMutableList()
                newList[idx] = updatedPage
                _uiState.update { it.copy(activePages = newList) }
                
                if (idx == 0) {
                    val doc = repository.getDocumentSync(page.documentId)
                    if (doc != null) {
                        repository.updateDocument(doc.copy(thumbnailPath = newPath))
                    }
                }
            }
        }
    }
"""
content = content.replace("fun deleteActivePage() {", func + "\n    fun deleteActivePage() {")

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
