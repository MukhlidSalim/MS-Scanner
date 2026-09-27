with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()
content = content.replace("filtered = filtered.filter { it.category == filter.category }", "filtered = filtered.filter { it.category == filter.category.name }")
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
