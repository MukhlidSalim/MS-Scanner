import os

filepath = 'app/src/main/java/com/example/ui/screens/home/HomeScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Add states for sort bottom sheet
state_old = "var showNewFolderDialog by remember { mutableStateOf(false) }"
state_new = "var showNewFolderDialog by remember { mutableStateOf(false) }\n    var showSortSheet by remember { mutableStateOf(false) }"
content = content.replace(state_old, state_new)

# Modify the TODO for sort
sort_todo_old = """                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_sort)) },
                                leadingIcon = { Icon(Icons.Default.Sort, null) },
                                onClick = { expanded = false; /* TODO: Show sort dialog */ }
                            )"""

sort_todo_new = """                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_sort)) },
                                leadingIcon = { Icon(Icons.Default.Sort, null) },
                                onClick = { expanded = false; showSortSheet = true }
                            )"""
content = content.replace(sort_todo_old, sort_todo_new)

# Add the BottomSheet at the end of the Scaffold
bottom_sheet_ui = """
    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.txt_sort), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                val currentSort = listUiState.sortMode
                
                val sortOptions = listOf(
                    com.example.ui.viewmodel.SortOrder.DATE_CREATED to "تاريخ الإنشاء (أحدث)",
                    com.example.ui.viewmodel.SortOrder.DATE_MODIFIED to "تاريخ التعديل (أحدث)",
                    com.example.ui.viewmodel.SortOrder.NAME to "الاسم (أ-ي)",
                    com.example.ui.viewmodel.SortOrder.SIZE to "الحجم (الأكبر)"
                )
                
                sortOptions.forEach { (order, label) ->
                    val isSelected = currentSort == order
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        onClick = {
                            listViewModel.setSortMode(order)
                            showSortSheet = false
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (order) {
                                    com.example.ui.viewmodel.SortOrder.DATE_CREATED -> Icons.Default.DateRange
                                    com.example.ui.viewmodel.SortOrder.DATE_MODIFIED -> Icons.Default.Update
                                    com.example.ui.viewmodel.SortOrder.NAME -> Icons.Default.SortByAlpha
                                    com.example.ui.viewmodel.SortOrder.SIZE -> Icons.Default.FormatSize
                                },
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
"""

# Place it right before the last closing brace of the file
# It's usually `}` after the last Dialog.
content = content.replace('    // GitHub In-App Update Dialog', bottom_sheet_ui + '\n    // GitHub In-App Update Dialog')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
