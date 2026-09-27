with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()
content = content.replace("BackupManager(context, repository.documentDao)", "BackupManager(context, repository)")
with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
