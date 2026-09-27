import os

filepath = 'app/src/main/java/com/example/ui/screens/home/HomeScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('                                  onLongClick = {\n                                      if (!selectionMode) {\n                                          selectionMode = true\n                                          selectedDocIds = selectedDocIds + doc.id\n                                      }\n                                  }', '                                  onLongClick = {\n                                      if (!selectionMode) {\n                                          selectionMode = true\n                                          selectedDocIds = selectedDocIds + doc.id\n                                      }\n                                  },\n                                  onDeleteClick = { listViewModel.deleteDocument(doc.id) }')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
