import re
with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

old_import = """                    if (processedPages.isNotEmpty()) {
                        viewModel.importPagesAsDocument(processedPages) { newDocId ->
                            onNavigateToDocument(newDocId)
                        }
                    }"""

new_import = """                    if (processedPages.isNotEmpty()) {
                        if (isIdCardMode && processedPages.size >= 2) {
                            val collagePath = ImageProcessor.createIdCardCollage(context, processedPages[0].second, processedPages[1].second, "idcard_proc_")
                            viewModel.importPagesAsDocument(listOf(Pair(processedPages[0].first, collagePath))) { newDocId ->
                                onNavigateToDocument(newDocId)
                            }
                        } else {
                            viewModel.importPagesAsDocument(processedPages) { newDocId ->
                                onNavigateToDocument(newDocId)
                            }
                        }
                    }"""

content = content.replace(old_import, new_import)

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
