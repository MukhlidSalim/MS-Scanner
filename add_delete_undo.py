import os
import re

filepath = 'app/src/main/java/com/example/ui/viewmodel/DocumentListViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# I will add deleteDocument method
new_method = """
    fun deleteDocument(docId: Long) {
        viewModelScope.launch {
            // Soft delete
            repository.moveToTrash(docId)
            refreshStorageStats()
            
            // Show Snackbar
            _events.send(UiEvent.ShowSnackbarWithAction(
                message = "تم حذف المستند",
                actionLabel = "تراجع",
                action = { undoDelete(docId) }
            ))
            
            // Wait 5 seconds
            kotlinx.coroutines.delay(5000)
            
            // Check if it's still in trash (not undone)
            val doc = repository.getDocumentById(docId)
            if (doc != null && doc.isTrash) {
                repository.deleteDocumentPermanently(docId)
                refreshStorageStats()
            }
        }
    }
    
    private fun undoDelete(docId: Long) {
        viewModelScope.launch {
            repository.restoreFromTrash(docId)
            refreshStorageStats()
            _events.send(UiEvent.ShowToast("تم التراجع عن الحذف"))
        }
    }
"""

content = content.replace('fun moveToTrash(docId: Long) {', new_method + '\n    fun moveToTrash(docId: Long) {')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
