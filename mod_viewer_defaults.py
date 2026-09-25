import re

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""    if \(showPdfExportDialog\) \{
        var selectedSize by remember \{ mutableStateOf\(PageSizePreset\.A4\) \}
        var selectedCompression by remember \{ mutableStateOf\(CompressionPreset\.HIGH\) \}"""

replacement = """    if (showPdfExportDialog) {
        val uiState by viewModel.uiState.collectAsState()
        var selectedSize by remember { mutableStateOf(uiState.defaultPdfPageSize) }
        var selectedCompression by remember { mutableStateOf(uiState.defaultPdfCompression) }"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
