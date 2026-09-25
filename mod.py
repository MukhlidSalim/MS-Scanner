import re

with open("app/src/main/java/com/example/ui/components/DocumentCard.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace(
    "fun DocumentCard(\n    document: DocumentEntity,\n    onClick: () -> Unit,\n    onToggleFavorite: () -> Unit,",
    "fun DocumentCard(\n    document: DocumentEntity,\n    onClick: () -> Unit,\n    onLongClick: (() -> Unit)? = null,\n    isSelected: Boolean = false,\n    onToggleFavorite: () -> Unit,"
)

content = content.replace(
    "@Composable\nfun DocumentCard",
    "import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.combinedClickable\n\n@OptIn(ExperimentalFoundationApi::class)\n@Composable\nfun DocumentCard"
)

content = content.replace(
    ".clickable(onClick = onClick)",
    ".combinedClickable(onClick = onClick, onLongClick = onLongClick)"
)

content = content.replace(
    "containerColor = MaterialTheme.colorScheme.surface",
    "containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface"
)

with open("app/src/main/java/com/example/ui/components/DocumentCard.kt", "w", encoding="utf-8") as f:
    f.write(content)
