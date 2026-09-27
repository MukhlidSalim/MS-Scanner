import re

filepath = 'app/src/main/java/com/example/ui/screens/editor/EditSessionScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Add states
content = content.replace(
    'var brightness by remember { mutableStateOf(0f) }',
    'var brightness by remember { mutableStateOf(0f) }\n    var hasUnsavedChanges by remember { mutableStateOf(false) }\n    var showDiscardDialog by remember { mutableStateOf(false) }'
)

# Add BackHandler and Dialog right after LaunchedEffect(Unit)
dialog_code = """
    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("تجاهل التعديلات؟") },
            text = { Text("توجد تعديلات غير محفوظة، هل أنت متأكد من الخروج؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        if (sourceType != "EXISTING") onClearPending()
                        else editViewModel.endEditingSession()
                        onNavigateBack()
                    }
                ) { Text("تجاهل التعديلات والخروج", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("متابعة التعديل") }
            }
        )
    }
"""

content = content.replace(
    '    if (showCropEditor) {',
    dialog_code + '\n    if (showCropEditor) {'
)

# Modify the TopAppBar cancel button
old_cancel = """                        TextButton(onClick = { 
                            if (sourceType != "EXISTING") onClearPending()
                            else editViewModel.endEditingSession()
                            onNavigateBack() 
                        }) { Text(stringResource(com.example.R.string.txt_cancel)) }"""

new_cancel = """                        TextButton(onClick = { 
                            if (hasUnsavedChanges) {
                                showDiscardDialog = true
                            } else {
                                if (sourceType != "EXISTING") onClearPending()
                                else editViewModel.endEditingSession()
                                onNavigateBack() 
                            }
                        }) { Text(stringResource(com.example.R.string.txt_cancel)) }"""

content = content.replace(old_cancel, new_cancel)

# Inject hasUnsavedChanges = true into Crop
content = content.replace(
    'editViewModel.updateEditingSessionPageProcessedImage(pagerState.currentPage, newProcessedPath)',
    'editViewModel.updateEditingSessionPageProcessedImage(pagerState.currentPage, newProcessedPath)\n                        hasUnsavedChanges = true'
)
content = content.replace(
    'onUpdatePendingPage(pagerState.currentPage, newProcessedPath)',
    'onUpdatePendingPage(pagerState.currentPage, newProcessedPath)\n                        hasUnsavedChanges = true'
)

# Inject hasUnsavedChanges = true into Filter
content = content.replace(
    'editViewModel.applyFilterToEditingSessionPage(pagerState.currentPage, it.name)',
    'editViewModel.applyFilterToEditingSessionPage(pagerState.currentPage, it.name)\n                                hasUnsavedChanges = true'
)
content = content.replace(
    'onApplyFilter(it.name, pagerState.currentPage, applyToAll)',
    'onApplyFilter(it.name, pagerState.currentPage, applyToAll)\n                                hasUnsavedChanges = true'
)

# Inject hasUnsavedChanges = true into Brightness
content = content.replace(
    'editViewModel.updateEditingSessionPageBrightness(pagerState.currentPage, v)',
    'editViewModel.updateEditingSessionPageBrightness(pagerState.currentPage, v)\n                                                    hasUnsavedChanges = true'
)

# Make sure it clears hasUnsavedChanges when saving (if needed) but navigating away happens anyway.

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
