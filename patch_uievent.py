import os

filepath = 'app/src/main/java/com/example/ui/util/UiEvent.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('data class ShowSnackbar(val message: String) : UiEvent()', 'data class ShowSnackbar(val message: String) : UiEvent()\n    data class ShowSnackbarWithAction(val message: String, val actionLabel: String, val action: () -> Unit) : UiEvent()')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
