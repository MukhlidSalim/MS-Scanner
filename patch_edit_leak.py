import os

filepath = 'app/src/main/java/com/example/ui/viewmodel/EditSessionViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

import re

# Add originalSessionPages variable
state_var_idx = content.find('private val _uiState = MutableStateFlow(EditSessionUiState())')
if state_var_idx != -1:
    content = content[:state_var_idx] + 'private var originalSessionPages = listOf<com.example.data.model.PageEntity>()\n    ' + content[state_var_idx:]

# Update startEditingSession
old_start = """    fun startEditingSession(pages: List<PageEntity>) {
        _uiState.update { it.copy(editingSessionPages = pages, isEditingSession = true) }
    }"""
new_start = """    fun startEditingSession(pages: List<PageEntity>) {
        originalSessionPages = pages
        _uiState.update { it.copy(editingSessionPages = pages, isEditingSession = true) }
    }"""
content = content.replace(old_start, new_start)

# Update endEditingSession
old_end = """    fun endEditingSession() {
        _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
    }"""
new_end = """    fun endEditingSession() {
        val currentPages = _uiState.value.editingSessionPages
        currentPages.forEach { currentPage ->
            val originalPage = originalSessionPages.find { it.id == currentPage.id }
            if (originalPage != null && currentPage.processedImagePath != originalPage.processedImagePath) {
                java.io.File(currentPage.processedImagePath).delete()
            }
        }
        originalSessionPages = emptyList()
        _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
    }"""
content = content.replace(old_end, new_end)

# Update commitEditingSessionChanges
old_commit = """    fun commitEditingSessionChanges(onComplete: () -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.editingSessionPages
            if (pages.isEmpty()) return@launch
            
            for (page in pages) {
                repository.updatePage(page)
            }
            
            endEditingSession()
            onComplete()
        }
    }"""
new_commit = """    fun commitEditingSessionChanges(onComplete: () -> Unit) {
        viewModelScope.launch {
            val pages = _uiState.value.editingSessionPages
            if (pages.isEmpty()) return@launch
            
            for (page in pages) {
                repository.updatePage(page)
                val originalPage = originalSessionPages.find { it.id == page.id }
                if (originalPage != null && originalPage.processedImagePath != page.processedImagePath) {
                    java.io.File(originalPage.processedImagePath).delete()
                }
            }
            
            originalSessionPages = emptyList()
            _uiState.update { it.copy(editingSessionPages = emptyList(), isEditingSession = false) }
            onComplete()
        }
    }"""
content = content.replace(old_commit, new_commit)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
