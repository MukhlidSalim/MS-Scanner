import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Fix duplicated import
content = content.replace("import android.content.Context\nimport android.content.Context", "import android.content.Context")

# Remove orphaned block for onSearchQueryChanged
content = re.sub(r'    fun onSearchQueryChanged\(query: String\) \{\n        _uiState\.update \{ it\.copy\(searchQuery = query\) \}\n        filterTrigger\.value = filterTrigger\.value\.copy\(query = query\)\n    \}\n        viewModelScope\.launch \{\n            if \(query\.isBlank\(\)\) \{.*?\n        \}\n    \}', r'    fun onSearchQueryChanged(query: String) {\n        _uiState.update { it.copy(searchQuery = query) }\n        filterTrigger.value = filterTrigger.value.copy(query = query)\n    }', content, flags=re.DOTALL)

# Remove orphaned block for filterByFolder
content = re.sub(r'    fun filterByFolder\(folder: String\) \{\n        _uiState\.update \{ it\.copy\(selectedFolder = folder\) \}\n        filterTrigger\.value = filterTrigger\.value\.copy\(folder = folder\)\n    \}\n        viewModelScope\.launch \{\n            if \(folder == "ALL"\) \{.*?\n        \}\n    \}', r'    fun filterByFolder(folder: String) {\n        _uiState.update { it.copy(selectedFolder = folder) }\n        filterTrigger.value = filterTrigger.value.copy(folder = folder)\n    }', content, flags=re.DOTALL)

# Remove orphaned block for setSortMode
content = re.sub(r'    fun setSortMode\(mode: SortMode\) \{\n        _uiState\.update \{ it\.copy\(sortMode = mode\) \}\n        filterTrigger\.value = filterTrigger\.value\.copy\(sort = mode\)\n    \}\n        val sorted = applySorting\(_uiState\.value\.documents, mode\)\n        _uiState\.update \{ state -> state\.copy\(documents = applySorting\(sorted, state\.sortMode\)\) \}\n    \}', r'    fun setSortMode(mode: SortMode) {\n        _uiState.update { it.copy(sortMode = mode) }\n        filterTrigger.value = filterTrigger.value.copy(sort = mode)\n    }', content, flags=re.DOTALL)

# Remove orphaned block for filterByCategory
content = re.sub(r'    fun filterByCategory\(cat: DocumentCategory\) \{\n        _uiState\.update \{ it\.copy\(selectedCategory = cat\) \}\n        filterTrigger\.value = filterTrigger\.value\.copy\(category = cat\)\n    \}\n        viewModelScope\.launch \{\n            if \(cat == DocumentCategory\.ALL\) \{.*?\n        \}\n    \}', r'    fun filterByCategory(cat: DocumentCategory) {\n        _uiState.update { it.copy(selectedCategory = cat) }\n        filterTrigger.value = filterTrigger.value.copy(category = cat)\n    }', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
