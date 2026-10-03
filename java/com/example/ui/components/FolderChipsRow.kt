package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.*

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
            val isSelected = folder == selectedFolder
            
            Box {
                FilterChip(
                    selected = isSelected,
                    onClick = { onFolderSelected(folder) },
                    shape = RoundedCornerShape(50),
                    label = {
                        Text(
                            text = if (folder == "ALL") stringResource(R.string.txt_all_folders) else folder,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (isSelected && folder != "ALL") {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.txt_options),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor      = Color.Transparent,
                        labelColor          = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconColor           = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = Color.Transparent,
                        selectedLabelColor  = GoldBase,
                        selectedLeadingIconColor = GoldBase,
                        selectedTrailingIconColor = GoldBase
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor         = MaterialTheme.colorScheme.outline,
                        selectedBorderColor = GoldBase,
                        borderWidth         = 0.75.dp,
                        selectedBorderWidth = 1.5.dp
                    ),
                    elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp)
                )
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface
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

        // New Folder Button
        OutlinedButton(
            onClick = onCreateFolderClick,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = GoldBase
            ),
            border = BorderStroke(0.75.dp, GoldBase.copy(alpha = 0.5f))
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.txt_new_folder),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    
    if (folderToRename != null) {
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    text = stringResource(R.string.txt_rename_folder),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRenameFolder(folderToRename!!, renameInput.trim())
                        }
                        folderToRename = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { folderToRename = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}
