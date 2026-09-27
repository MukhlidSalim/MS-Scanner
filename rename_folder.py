import re
with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

folder_item_old = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridItem(
    folderName: String,
    documentCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,"""

folder_item_new = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridItem(
    folderName: String,
    documentCount: Int,
    onClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,"""

content = content.replace(folder_item_old, folder_item_new)

folder_inner_old = """            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = CyanScan
            )"""

folder_inner_new = """            Box(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).align(Alignment.Center),
                    tint = CyanScan
                )
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = Color.Gray)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.txt_rename_folder)) },
                            onClick = {
                                expanded = false
                                onRenameClick()
                            }
                        )
                    }
                }
            }"""

content = content.replace(folder_inner_old, folder_inner_new)

content = content.replace("onClick = { viewModel.filterByFolder(folder) }", "onClick = { viewModel.filterByFolder(folder) },\n                                onRenameClick = { renameFolderTarget = folder; newFolderRename = folder }")

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
