import re
with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("var updateInfo by remember { mutableStateOf<com.example.engine.updater.AppUpdater.UpdateInfo?>(null) }", """
    var updateInfo by remember { mutableStateOf<com.example.engine.updater.AppUpdater.UpdateInfo?>(null) }
    var showCameraSheet by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var newFolderRename by remember { mutableStateOf("") }
""")

sheet_code = """
    if (showCameraSheet) {
        ModalBottomSheet(onDismissRequest = { showCameraSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(stringResource(R.string.txt_scan_doc), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.txt_scan_doc)) },
                    leadingContent = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                    modifier = Modifier.clickable {
                        isIdCardMode = false
                        showCameraSheet = false
                        launchScanner(20)
                    }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.txt_scan_id_card)) },
                    leadingContent = { Icon(Icons.Default.Badge, contentDescription = null) },
                    modifier = Modifier.clickable {
                        isIdCardMode = true
                        showCameraSheet = false
                        launchScanner(2) // Limit 2 for front/back
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
"""

content = content.replace("if (showTrashDialog) {", sheet_code + "\n    if (showTrashDialog) {")

rename_dialog = """
    if (renameFolderTarget != null) {
        AlertDialog(
            onDismissRequest = { renameFolderTarget = null },
            title = { Text(stringResource(R.string.txt_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderRename,
                    onValueChange = { newFolderRename = it },
                    label = { Text(stringResource(R.string.txt_new_folder_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderRename.isNotBlank()) {
                        viewModel.renameFolder(renameFolderTarget!!, newFolderRename)
                    }
                    renameFolderTarget = null
                }) { Text(stringResource(R.string.txt_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameFolderTarget = null }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
"""
content = content.replace("if (showTrashDialog) {", rename_dialog + "\n    if (showTrashDialog) {")

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
