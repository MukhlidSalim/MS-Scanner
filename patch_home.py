with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("GmsDocumentScanningOptions", "GmsDocumentScannerOptions")
content = content.replace("viewModel.sharePdf(context, it)", "viewModel.shareDocumentsAsPdf(context, listOf(it))")
content = content.replace("selectedDocIds.forEach { viewModel.shareDocumentsAsPdf(context, listOf(it)) }", "viewModel.shareDocumentsAsPdf(context, selectedDocIds.toList())")
content = content.replace("Icons.Outlined.GridView", "Icons.Default.FolderOpen")
content = content.replace("import androidx.compose.material.icons.outlined.GridView\n", "")

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
