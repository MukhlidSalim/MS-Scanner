import os

filepath = 'app/src/main/java/com/example/ui/viewmodel/CameraViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_clear = """    fun clearPendingPages() {
        _uiState.update { it.copy(pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList()) }
    }"""

new_clear = """    fun clearPendingPages(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            val state = _uiState.value
            state.pendingPages.forEach { 
                java.io.File(it.first).delete()
                java.io.File(it.second).delete()
            }
            state.pagesPendingEdit.forEach { 
                java.io.File(it.first).delete()
                java.io.File(it.second).delete()
            }
        }
        _uiState.update { it.copy(pendingPages = emptyList(), pagesPendingEdit = emptyList(), importedUrisPending = emptyList()) }
    }"""

content = content.replace(old_clear, new_clear)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
