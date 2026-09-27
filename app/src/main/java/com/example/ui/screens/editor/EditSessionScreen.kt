package com.example.ui.screens.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.ui.viewmodel.EditSessionViewModel
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Title
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.ui.theme.Emerald400

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditSessionScreen(
    editViewModel: EditSessionViewModel,
    // Camera responsibility data/actions
    pagesPendingEdit: List<Pair<String, String>> = emptyList(),
    cameraIsLoading: Boolean = false,
    onUpdatePendingPage: (Int, String) -> Unit = { _, _ -> },
    onRotatePendingPage: (Int, Boolean) -> Unit = { _, _ -> },
    onAnalyzePending: () -> Unit = {},
    onCommitPending: (Long, () -> Unit) -> Unit = { _, _ -> },
    onImportPages: (String, String, (Long) -> Unit) -> Unit = { _, _, _ -> },
    onClearPending: () -> Unit = {},
    // List responsibility data
    selectedFolder: String = "Default",
    // Navigation
    sourceType: String, // "CAMERA", "IMPORT", "EXISTING"
    docId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToFinish: (Long) -> Unit
) {
    val editUiState by editViewModel.uiState.collectAsState()
    
    // Determine which pages to show
    val pages: List<Any> = when (sourceType) {
        "EXISTING" -> editUiState.activePages
        else -> pagesPendingEdit
    }
    
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // If source is EXISTING and not already in editing session, load it
    LaunchedEffect(sourceType, docId) {
        if (sourceType == "EXISTING" && editUiState.activeDocument?.id != docId) {
            editViewModel.loadDocument(docId)
        }
    }
    
    // When active pages are loaded for existing doc, start editing session if not already started
    LaunchedEffect(editUiState.activePages) {
        if (sourceType == "EXISTING" && editUiState.activePages.isNotEmpty() && !editUiState.isEditingSession) {
            editViewModel.startEditingSession(editUiState.activePages)
        }
    }
    
    // Auto-analyze first page for classification and OCR metadata
    LaunchedEffect(pagesPendingEdit) {
        if (sourceType != "EXISTING" && pagesPendingEdit.isNotEmpty()) {
            onAnalyzePending()
        }
    }

    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && pagerState.currentPage >= pages.size) {
            pagerState.scrollToPage(0)
        }
    }
    
    var documentTitle by remember { 
        mutableStateOf(
            if (sourceType == "EXISTING") editUiState.activeDocument?.title ?: "" 
            else "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        ) 
    }
    var showCropEditor by remember { mutableStateOf(false) }
    var applyToAll by remember { mutableStateOf(false) }
    var brightness by remember { mutableStateOf(0f) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var contrast by remember { mutableStateOf(1f) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        editViewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.ShowToast -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }


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

    if (showCropEditor) {
        val currentPage = pages.getOrNull(pagerState.currentPage)
        val imagePath = when (currentPage) {
            is com.example.data.model.PageEntity -> currentPage.rawImagePath
            is Pair<*, *> -> (currentPage as Pair<String, String>).first
            else -> ""
        }
        
        if (imagePath.isNotEmpty()) {
            DocumentCropEditorScreen(
                imagePath = imagePath,
                onCropped = { newProcessedPath ->
                    if (sourceType == "EXISTING") {
                        editViewModel.updateEditingSessionPageProcessedImage(pagerState.currentPage, newProcessedPath)
                        hasUnsavedChanges = true
                    } else {
                        onUpdatePendingPage(pagerState.currentPage, newProcessedPath)
                        hasUnsavedChanges = true
                    }
                    showCropEditor = false
                },
                onCancel = { showCropEditor = false }
            )
        } else {
            showCropEditor = false
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { 
                        val current = if (pages.isEmpty()) 0 else pagerState.currentPage + 1
                        Text("Review Pages ($current/${pages.size})") 
                    },
                    actions = {
                        TextButton(onClick = { 
                            if (hasUnsavedChanges) {
                                showDiscardDialog = true
                            } else {
                                if (sourceType != "EXISTING") onClearPending()
                                else editViewModel.endEditingSession()
                                onNavigateBack() 
                            }
                        }) { Text(stringResource(com.example.R.string.txt_cancel)) }
                        Button(
                            onClick = {
                                if (sourceType == "EXISTING") {
                                    editViewModel.commitEditingSessionChanges {
                                        onNavigateToFinish(docId)
                                    }
                                } else if (docId > 0L) {
                                    onCommitPending(docId) {
                                        onNavigateToFinish(docId)
                                    }
                                } else {
                                    onImportPages(documentTitle, selectedFolder) { newDocId ->
                                        onNavigateToFinish(newDocId)
                                    }
                                }
                            },
                            enabled = pages.isNotEmpty() && !editUiState.isLoading && !cameraIsLoading
                        ) {
                            Text(stringResource(com.example.R.string.txt_save))
                        }
                    }
                )
            }
        ) { padding ->
            if (editUiState.isLoading || cameraIsLoading) {
                Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pages.isEmpty()) {
                Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No pages to display")
                }
            } else {
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    // Title Editor
                    if (sourceType != "EXISTING") {
                        OutlinedTextField(
                            value = documentTitle,
                            onValueChange = { documentTitle = it },
                            label = { Text("Document Title") },
                            leadingIcon = { Icon(Icons.Default.Title, null) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            singleLine = true
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f)
                    ) { pageIndex ->
                        val page = pages.getOrNull(pageIndex)
                        val imagePath = when (page) {
                            is com.example.data.model.PageEntity -> page.processedImagePath
                            is Pair<*, *> -> (page as Pair<String, String>).second
                            else -> ""
                        }
                        
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (imagePath.isNotEmpty()) {
                                AsyncImage(
                                    model = File(imagePath),
                                    contentDescription = "Page ${pageIndex + 1}",
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                        }
                    }
                    
                    // Edit Controls
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                            Text("Apply adjustments to all pages")
                        }
                        
                        Text("Brightness")
                        Slider(value = brightness, onValueChange = { brightness = it; hasUnsavedChanges = true }, valueRange = -1f..1f)
                        
                        Text("Contrast")
                        Slider(value = contrast, onValueChange = { contrast = it; hasUnsavedChanges = true }, valueRange = 0f..2f)
                        
                        Text("PDF Compression")
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            com.example.data.model.CompressionPreset.values().forEach { preset ->
                                FilterChip(
                                    selected = editUiState.selectedCompression == preset,
                                    onClick = { editViewModel.setCompression(preset) },
                                    label = { Text(preset.name) }
                                )
                            }
                        }

                        Text("OCR Language")
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            com.example.engine.ocr.OcrLanguage.values().forEach { language ->
                                FilterChip(
                                    selected = editUiState.ocrLanguage == language,
                                    onClick = { editViewModel.setOcrLanguage(language) },
                                    label = { Text(language.displayName) }
                                )
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (sourceType == "EXISTING") {
                                    editViewModel.rotateEditingSessionPage(pagerState.currentPage, false)
                                } else {
                                    onRotatePendingPage(pagerState.currentPage, false)
                                }
                            }) {
                                Icon(Icons.Default.RotateLeft, contentDescription = "Rotate Left")
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Button(
                                onClick = { showCropEditor = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black)
                            ) {
                                Icon(Icons.Default.Crop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Crop & Adjust")
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            IconButton(onClick = {
                                if (sourceType == "EXISTING") {
                                    editViewModel.rotateEditingSessionPage(pagerState.currentPage, true)
                                } else {
                                    onRotatePendingPage(pagerState.currentPage, true)
                                }
                            }) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate Right")
                            }
                        }
                    }
                }
            }
        }
    }
}
