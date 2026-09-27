import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

replacement = """fun addPagesToCurrentDocument(newPages: List<Pair<String, String>>) {
        viewModelScope.launch {
            val doc = _uiState.value.activeDocument ?: return@launch
            var pageCount = doc.pageCount
            for (pagePair in newPages) {
                val newPage = com.example.data.model.PageEntity(
                    documentId = doc.id,
                    pageIndex = pageCount,
                    rawImagePath = pagePair.first,
                    processedImagePath = pagePair.second
                )
                repository.insertPage(newPage)
                pageCount++
            }
            repository.updateDocument(doc.copy(pageCount = pageCount))
            // Refresh from DB
            val dbPages = repository.getPagesList(doc.id)
            _uiState.update { it.copy(activePages = dbPages) }
        }
    }"""

content = re.sub(r'fun addPagesToCurrentDocument\(newPages: List<Pair<String, String>>\) \{.*?\}', replacement, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
