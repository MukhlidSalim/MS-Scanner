import re
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

replacement = """            val docs = docIds.mapNotNull { id -> _uiState.value.documents.find { it.id == id } }
            val pdfTitle = if (docs.size == 1) docs.first().title else if (docs.isNotEmpty()) docs.first().title + "_Shared" else "Shared_Documents"
            
            val config = com.example.engine.pdf.PdfExportConfig(
                title = pdfTitle,
                pageSize = _uiState.value.defaultPdfPageSize,
                compression = _uiState.value.defaultPdfCompression
            )"""

content = re.sub(
    r"            val config = com\.example\.engine\.pdf\.PdfExportConfig\(\s*pageSize = _uiState\.value\.defaultPdfPageSize,\s*compression = _uiState\.value\.defaultPdfCompression\s*\)",
    replacement,
    content
)

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
