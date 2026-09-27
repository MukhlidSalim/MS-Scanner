with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()
content = content.replace("com.example.data.model.DocumentQuad", "DocumentQuad")
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
