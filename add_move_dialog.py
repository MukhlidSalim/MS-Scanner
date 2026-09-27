import re
with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

move_dialog = """    if (docToMove != null) {
        var selectedFolderDest by remember { mutableStateOf("Default") }
        AlertDialog(
            onDismissRequest = { docToMove = null },
            title = { Text(stringResource(R.string.txt_move_to_folder)) },
            text = {
                Column {
                    uiState.folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedFolderDest == folder, onClick = { selectedFolderDest = folder })
                            Text(folder)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedDocIds.isNotEmpty()) {
                        selectedDocIds.forEach { viewModel.changeDocumentFolder(it, selectedFolderDest) }
                    } else {
                        viewModel.changeDocumentFolder(docToMove!!, selectedFolderDest)
                    }
                    docToMove = null
                    selectionMode = false
                    selectedDocIds = emptySet()
                }) { Text(stringResource(R.string.txt_save)) }
            },
            dismissButton = {
                TextButton(onClick = { docToMove = null }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
"""

content = content.replace("if (showTrashDialog) {", move_dialog + "\n    if (showTrashDialog) {")

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
