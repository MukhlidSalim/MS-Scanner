import re

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

import_pattern = r"""import com.example.ui.components.CategoryChipsRow"""
import_replacement = """import com.example.ui.components.CategoryChipsRow\nimport com.example.ui.components.FolderChipsRow"""
content = re.sub(import_pattern, import_replacement, content)

chips_pattern = r"""            // Category Filter Chips
            CategoryChipsRow\(
                selectedCategory = uiState\.selectedCategory,
                onCategorySelected = \{ viewModel\.filterByCategory\(it\) \}
            \)"""

chips_replacement = """            // Folder Chips
            if (uiState.folders.isNotEmpty()) {
                FolderChipsRow(
                    folders = uiState.folders,
                    selectedFolder = uiState.selectedFolder,
                    onFolderSelected = { viewModel.filterByFolder(it) }
                )
            }

            // Category Filter Chips
            CategoryChipsRow(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.filterByCategory(it) }
            )"""

content = re.sub(chips_pattern, chips_replacement, content)

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
