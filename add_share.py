with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

share_func = """
    fun shareDocumentsAsPdf(context: android.content.Context, docIds: List<Long>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingPdf = true) }
            val allPages = mutableListOf<com.example.data.model.PageEntity>()
            for (id in docIds) {
                allPages.addAll(repository.getPagesForDocumentSync(id))
            }
            if (allPages.isEmpty()) {
                _uiState.update { it.copy(isExportingPdf = false) }
                return@launch
            }
            
            val config = com.example.engine.pdf.PdfExportConfig(
                pageSize = _uiState.value.defaultPdfPageSize,
                compression = _uiState.value.defaultPdfCompression
            )
            val pairs = allPages.map { Pair(it.rawImagePath, it.processedImagePath) }
            val pdfFile = com.example.engine.pdf.PdfEngine.generatePdf(context, pairs, config)
            
            _uiState.update { it.copy(isExportingPdf = false) }
            
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", pdfFile)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "application/pdf"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share PDF via"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
"""

if "fun shareDocumentsAsPdf" not in content:
    content = content.replace("fun deleteActivePage() {", share_func + "\n    fun deleteActivePage() {")

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
