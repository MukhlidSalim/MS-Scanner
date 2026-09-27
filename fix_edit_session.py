import os

filepath = 'app/src/main/java/com/example/ui/viewmodel/EditSessionViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = """                if (idx == 0) {
                    val doc = repository.getDocumentById(page.documentId)
                    if (doc != null) {
                        repository.updateDocument(doc.copy(thumbnailPath = newPath))
                    }
                }"""

new_block = """                if (idx == 0) {
                    val doc = repository.getDocumentById(page.documentId)
                    if (doc != null) {
                        // Delete old thumbnail if needed, though typically done in repo, here we just overwrite/create a new one
                        doc.thumbnailPath?.let { try { java.io.File(it).delete() } catch(e: Exception) {} }
                        val thumb = ImageProcessor.createThumbnail(context, newPath) ?: newPath
                        repository.updateDocument(doc.copy(thumbnailPath = thumb))
                    }
                }"""

content = content.replace(old_block, new_block)

# Also fix replacePage which might need thumbnail update
old_block_2 = """            repository.updatePage(updatedPage)
            
            val newList = _uiState.value.activePages.toMutableList()
            val idx = newList.indexOfFirst { it.id == pageId }
            if (idx != -1) {
                newList[idx] = updatedPage
                _uiState.update { it.copy(activePages = newList) }
            }"""

new_block_2 = """            repository.updatePage(updatedPage)
            
            val newList = _uiState.value.activePages.toMutableList()
            val idx = newList.indexOfFirst { it.id == pageId }
            if (idx != -1) {
                newList[idx] = updatedPage
                _uiState.update { it.copy(activePages = newList) }
                
                if (idx == 0) {
                    val doc = repository.getDocumentById(updatedPage.documentId)
                    if (doc != null) {
                        doc.thumbnailPath?.let { try { java.io.File(it).delete() } catch(e: Exception) {} }
                        val thumb = ImageProcessor.createThumbnail(context, newProcessedPath) ?: newProcessedPath
                        repository.updateDocument(doc.copy(thumbnailPath = thumb))
                    }
                }
            }"""

content = content.replace(old_block_2, new_block_2)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
