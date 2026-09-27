import re

with open('app/src/main/java/com/example/ui/components/DocumentCard.kt', 'r', encoding='utf-8') as f:
    content = f.read()

imports = "import androidx.compose.ui.res.stringResource\nimport com.example.R\n"
content = content.replace("import androidx.compose.ui.unit.sp\n", "import androidx.compose.ui.unit.sp\n" + imports)

with open('app/src/main/java/com/example/ui/components/DocumentCard.kt', 'w', encoding='utf-8') as f:
    f.write(content)
