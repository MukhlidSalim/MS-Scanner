import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace loadAllDocuments, setFolder, filterByCategory with combine logic
combine_import = "import kotlinx.coroutines.flow.combine\nimport kotlinx.coroutines.flow.flatMapLatest\n"
content = content.replace("import kotlinx.coroutines.flow.asStateFlow", combine_import + "import kotlinx.coroutines.flow.asStateFlow")

filter_state = """
    private data class FilterState(
        val folder: String = "ALL",
        val category: DocumentCategory = DocumentCategory.ALL,
        val query: String = "",
        val sort: SortMode = SortMode.NEWEST
    )
    private val filterTrigger = MutableStateFlow(FilterState())
"""

content = content.replace('init {', filter_state + '\n    init {')

# Find init block and replace loadAllDocuments call
content = content.replace('loadAllDocuments()', 'setupDocumentStream()')

setup_stream = """
    private fun setupDocumentStream() {
        viewModelScope.launch {
            repository.getAllDocuments()
                .combine(filterTrigger) { docs, filter ->
                    var filtered = docs
                    if (filter.folder != "ALL") filtered = filtered.filter { it.folderName == filter.folder }
                    if (filter.category != DocumentCategory.ALL) filtered = filtered.filter { it.category == filter.category.name }
                    if (filter.query.isNotBlank()) filtered = filtered.filter { it.title.contains(filter.query, ignoreCase = true) }
                    applySorting(filtered, filter.sort)
                }
                .collectLatest { sortedDocs ->
                    _uiState.update { it.copy(documents = sortedDocs) }
                }
        }
    }
"""

content = content.replace('private fun loadAllDocuments() {', setup_stream + '\n    private fun old_loadAllDocuments() {')

# Delete old filtering methods
content = re.sub(r'fun onSearchQueryChanged\(query: String\) \{.*?\}', 'fun onSearchQueryChanged(query: String) {\n        _uiState.update { it.copy(searchQuery = query) }\n        filterTrigger.value = filterTrigger.value.copy(query = query)\n    }', content, flags=re.DOTALL)
content = re.sub(r'fun filterByFolder\(folder: String\) \{.*?\}', 'fun filterByFolder(folder: String) {\n        _uiState.update { it.copy(selectedFolder = folder) }\n        filterTrigger.value = filterTrigger.value.copy(folder = folder)\n    }', content, flags=re.DOTALL)
content = re.sub(r'fun filterByCategory\(cat: DocumentCategory\) \{.*?\}', 'fun filterByCategory(cat: DocumentCategory) {\n        _uiState.update { it.copy(selectedCategory = cat) }\n        filterTrigger.value = filterTrigger.value.copy(category = cat)\n    }', content, flags=re.DOTALL)
content = re.sub(r'fun setSortMode\(mode: SortMode\) \{.*?\}', 'fun setSortMode(mode: SortMode) {\n        _uiState.update { it.copy(sortMode = mode) }\n        filterTrigger.value = filterTrigger.value.copy(sort = mode)\n    }', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
