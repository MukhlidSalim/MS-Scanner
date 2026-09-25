import re

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""    fun exportDocumentToPdf\(config: PdfExportConfig, onComplete: \(File\) -> Unit\) \{
        viewModelScope\.launch \{
            _uiState\.update \{ it\.copy\(isExportingPdf = true\) \}
            val pages = _uiState\.value\.activePages
            val pairs = pages\.map \{ Pair\(it\.processedImagePath, it\.ocrText\) \}"""

replacement = """    fun exportDocumentToPdf(config: PdfExportConfig, selectedPageIds: Set<Long>? = null, onComplete: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            val pages = if (selectedPageIds != null && selectedPageIds.isNotEmpty()) {
                _uiState.value.activePages.filter { selectedPageIds.contains(it.id) }
            } else {
                _uiState.value.activePages
            }
            val pairs = pages.map { Pair(it.processedImagePath, it.ocrText) }"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
