import os

filepath = 'app/src/main/java/com/example/ui/screens/home/HomeScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = """                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}"""

new_block = """                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.ShowSnackbarWithAction -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.action()
                    }
                }
                is com.example.ui.util.UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}"""

content = content.replace(old_block, new_block)

# Replace listViewModel.moveToTrash(it) with listViewModel.deleteDocument(it)
content = content.replace('listViewModel.moveToTrash(it)', 'listViewModel.deleteDocument(it)')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
