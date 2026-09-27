import os

filepath = 'app/src/main/java/com/example/ui/viewmodel/DocumentListViewModel.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Add the enum
new_enum = """enum class SortOrder {
    DATE_CREATED,
    DATE_MODIFIED,
    NAME,
    SIZE
}"""

# Replace import of SortMode if present
content = content.replace('import com.example.data.model.SortMode', '')

# Replace SortMode with SortOrder in UI State
content = content.replace('val sortMode: SortMode = SortMode.NEWEST', 'val sortMode: SortOrder = SortOrder.DATE_MODIFIED')
content = content.replace('val sort: SortMode = SortMode.NEWEST', 'val sort: SortOrder = SortOrder.DATE_MODIFIED')

# Replace setSortMode
content = content.replace('fun setSortMode(mode: SortMode)', 'fun setSortMode(mode: SortOrder)')

# Replace applySorting
old_apply = """    private fun applySorting(docs: List<DocumentEntity>, mode: SortMode): List<DocumentEntity> {
        return when (mode) {
            SortMode.NEWEST -> docs.sortedByDescending { it.updatedAt }
            SortMode.OLDEST -> docs.sortedBy { it.updatedAt }
            SortMode.NAME_AZ -> docs.sortedBy { it.title.lowercase() }
            SortMode.NAME_ZA -> docs.sortedByDescending { it.title.lowercase() }
            SortMode.SIZE_LARGEST -> docs.sortedByDescending { it.sizeBytes }
            SortMode.PAGE_COUNT -> docs.sortedByDescending { it.pageCount }
        }
    }"""

new_apply = """    private fun applySorting(docs: List<DocumentEntity>, mode: SortOrder): List<DocumentEntity> {
        return docs.sortedWith(
            when (mode) {
                SortOrder.DATE_CREATED -> compareByDescending { it.createdAt }
                SortOrder.DATE_MODIFIED -> compareByDescending { it.updatedAt }
                SortOrder.NAME -> compareBy { it.title.lowercase() }
                SortOrder.SIZE -> compareByDescending { it.sizeBytes }
            }
        )
    }"""

content = content.replace(old_apply, new_apply)

# Add enum at the end or top
content = content.replace('package com.example.ui.viewmodel\n', 'package com.example.ui.viewmodel\n\n' + new_enum + '\n')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
