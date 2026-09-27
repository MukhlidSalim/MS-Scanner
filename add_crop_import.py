import re
with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

if "import androidx.compose.material.icons.filled.Crop" not in content:
    content = content.replace("import androidx.compose.material.icons.filled.ColorLens", "import androidx.compose.material.icons.filled.ColorLens\nimport androidx.compose.material.icons.filled.Crop")

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
