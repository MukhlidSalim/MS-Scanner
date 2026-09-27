import re
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("val doc = repository.getDocumentSync(page.documentId)", "val doc = repository.getDocumentById(page.documentId)")

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
