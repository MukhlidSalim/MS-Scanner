import re
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

merge_func = """
    fun mergeDocuments(docIds: List<Long>) {
        if (docIds.size < 2) return
        viewModelScope.launch {
            val docs = docIds.mapNotNull { id -> _uiState.value.documents.find { it.id == id } }
            if (docs.size < 2) return@launch
            
            val allPages = mutableListOf<com.example.data.model.PageEntity>()
            for (doc in docs) {
                allPages.addAll(repository.getPagesForDocumentSync(doc.id))
            }
            
            val newTitle = docs.first().title + "_Merged"
            val newDocId = repository.createDocumentWithPages(
                title = newTitle,
                folderName = _uiState.value.selectedFolder.takeIf { it != "ALL" } ?: "Default",
                category = "OTHER",
                pages = allPages.map { Pair(it.rawImagePath, it.processedImagePath) }
            )
            refreshStorageStats()
        }
    }
"""
if "fun mergeDocuments" not in content:
    content = content.replace("fun deleteActivePage() {", merge_func + "\n    fun deleteActivePage() {")
    with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
        f.write(content)
