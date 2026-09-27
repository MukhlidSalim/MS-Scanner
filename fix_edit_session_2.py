import os

filepath = 'app/src/main/java/com/example/ui/viewmodel/EditSessionViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = """        viewModelScope.launch {
            val page = _uiState.value.activePages.find { it.id == pageId } ?: return@launch
            val updatedPage = page.copy(
                rawImagePath = newRawPath,
                processedImagePath = newProcessedPath,
                cropQuadJson = "",
                rotationDegrees = 0
            )
            repository.updatePage(updatedPage)
            qualityCache.remove(pageId)
            loadDocument(page.documentId)
        }"""

new_block = """        viewModelScope.launch {
            val page = _uiState.value.activePages.find { it.id == pageId } ?: return@launch
            val updatedPage = page.copy(
                rawImagePath = newRawPath,
                processedImagePath = newProcessedPath,
                cropQuadJson = "",
                rotationDegrees = 0
            )
            repository.updatePage(updatedPage)
            
            // Update thumbnail if it's the first page
            val idx = _uiState.value.activePages.indexOfFirst { it.id == pageId }
            if (idx == 0) {
                val doc = repository.getDocumentById(page.documentId)
                if (doc != null) {
                    doc.thumbnailPath?.let { try { java.io.File(it).delete() } catch(e: Exception) {} }
                    val thumb = ImageProcessor.createThumbnail(context, newProcessedPath) ?: newProcessedPath
                    repository.updateDocument(doc.copy(thumbnailPath = thumb))
                }
            }
            
            qualityCache.remove(pageId)
            loadDocument(page.documentId)
        }"""

content = content.replace(old_block, new_block)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
