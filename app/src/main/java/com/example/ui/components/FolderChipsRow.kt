package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderChipsRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
    onCreateFolderClick: () -> Unit,
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var folderToRename by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val allFolders = listOf("ALL") + folders.filter { it != "ALL" }
        allFolders.forEach { folder ->
            var showMenu by remember { mutableStateOf(false) }
            
            Box {
                FilterChip(
                    selected = folder == selectedFolder,
                    onClick = { onFolderSelected(folder) },
                    label = { Text(if (folder == "ALL") stringResource(R.string.txt_all_folders) else folder) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (folder == selectedFolder && folder != "ALL") {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.txt_options))
                            }
                        }
                    }
                )
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.txt_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            showMenu = false
                            folderToRename = folder
                            renameInput = folder
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.txt_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteFolder(folder)
                        }
                    )
                }
            }
        }

        InputChip(
            selected = false,
            onClick = onCreateFolderClick,
            label = { Text(stringResource(R.string.txt_new_folder)) },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
    }
    
    if (folderToRename != null) {
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.txt_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameInput.isNotBlank()) {
                        onRenameFolder(folderToRename!!, renameInput.trim())
                    }
                    folderToRename = null
                }) { Text(stringResource(R.string.txt_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.txt_cancel)) }
            }
        )
    }
}
