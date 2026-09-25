import re

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""                        viewModel\.exportDocumentToPdf\(config\) \{ generatedPdf ->
                            showPdfExportDialog = false
                            shareFile\(context, generatedPdf, "application/pdf"\)
                        \}"""

replacement = """                        viewModel.exportDocumentToPdf(config, if (selectionMode) selectedPageIds else null) { generatedPdf ->
                            showPdfExportDialog = false
                            if (selectionMode) {
                                selectionMode = false
                                selectedPageIds = emptySet()
                            }
                            shareFile(context, generatedPdf, "application/pdf")
                        }"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
