import re

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Replace DocumentCard call in both list and grid
pattern = r"""DocumentCard\(\s*document = doc,\s*onClick = \{ onNavigateToDocument\(doc\.id\) \},"""
replacement = """DocumentCard(
                            document = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            onClick = { 
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id) 
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedDocIds = setOf(doc.id)
                                }
                            },"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
