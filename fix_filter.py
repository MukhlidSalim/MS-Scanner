import re
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

new_setup = """    private fun setupDocumentStream() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.getAllDocuments(),
                filterTrigger
            ) { docs, filter ->
                var filtered = docs
                if (filter.folder != "ALL") {
                    filtered = filtered.filter { it.folderName == filter.folder }
                }
                if (filter.query.isNotBlank()) {
                    filtered = filtered.filter { it.title.contains(filter.query, ignoreCase = true) }
                }
                applySorting(filtered, filter.sort)
            }.collectLatest { sortedAndFiltered ->
                _uiState.update { it.copy(documents = sortedAndFiltered) }
            }
        }"""

content = re.sub(
    r"    private fun setupDocumentStream\(\) \{[\s\S]*?\.collectLatest \{ sortedAndFiltered ->\n\s*_uiState\.update \{ it\.copy\(documents = sortedAndFiltered\) \}\n\s*\}\n\s*\}",
    new_setup,
    content
)

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
