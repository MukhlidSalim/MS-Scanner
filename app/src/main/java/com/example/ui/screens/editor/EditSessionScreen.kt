package com.example.ui.screens.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.ui.viewmodel.DocumentViewModel
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
    viewModel: DocumentViewModel,
    sourceType: String, // "CAMERA", "IMPORT", "EXISTING"
    docId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToFinish: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Determine which pages to show
    val pages: List<Any> = when (sourceType) {
        "EXISTING" -> uiState.activePages
        else -> uiState.pagesPendingEdit
    }
    
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // If source is EXISTING and not already in editing session, load it
    LaunchedEffect(sourceType, docId) {
        if (sourceType == "EXISTING" && uiState.activeDocument?.id != docId) {
            viewModel.loadDocument(docId)
        }
    }
    
    // When active pages are loaded for existing doc, start editing session if not already started
    LaunchedEffect(uiState.activePages) {
        if (sourceType == "EXISTING" && uiState.activePages.isNotEmpty() && !uiState.isEditingSession) {
            viewModel.startEditingSession(uiState.activePages)
        }
    }

    // Auto-analyze first page for classification and OCR metadata
    LaunchedEffect(uiState.pagesPendingEdit) {
        if (sourceType != "EXISTING" && uiState.pagesPendingEdit.isNotEmpty()) {
            viewModel.analyzePendingFirstPage()
        }
    }

    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && pagerState.currentPage >= pages.size) {
            pagerState.scrollToPage(0)
        }
    }
    
    var documentTitle by remember { 
        mutableStateOf(
            if (sourceType == "EXISTING") uiState.activeDocument?.title ?: "" 
            else "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        ) 
    }
    var showCropEditor by remember { mutableStateOf(false) }
    var applyToAll by remember { mutableStateOf(false) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }

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
                        viewModel.updateEditingSessionPageProcessedImage(pagerState.currentPage, newProcessedPath)
                    } else {
                        viewModel.updatePendingPageProcessedImage(pagerState.currentPage, newProcessedPath)
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
            topBar = {
                TopAppBar(
                    title = { 
                        val current = if (pages.isEmpty()) 0 else pagerState.currentPage + 1
                        Text("Review Pages ($current/${pages.size})") 
                    },
                    actions = {
                        TextButton(onClick = { 
                            if (sourceType != "EXISTING") viewModel.clearPendingPages()
                            else viewModel.endEditingSession()
                            onNavigateBack() 
                        }) { Text(stringResource(com.example.R.string.txt_cancel)) }
                        Button(
                            onClick = {
                                if (sourceType == "EXISTING") {
                                    // Optionally update title if changed? 
                                    // For now just commit changes
                                    viewModel.commitEditingSessionChanges {
                                        onNavigateToFinish(docId)
                                    }
                                } else if (docId > 0L) {
                                    viewModel.commitPendingPagesToDocument(docId) {
                                        onNavigateToFinish(docId)
                                    }
                                } else {
                                    viewModel.importPagesAsDocument(uiState.pagesPendingEdit, documentTitle) { newDocId ->
                                        viewModel.clearPendingPages()
                                        onNavigateToFinish(newDocId)
                                    }
                                }
                            },
                            enabled = pages.isNotEmpty() && !uiState.isLoading
                        ) {
                            Text(stringResource(com.example.R.string.txt_save))
                        }
                    }
                )
            }
        ) { padding ->
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -1f..1f)
                    
                    Text("Contrast")
                    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0f..2f)
                    
                    Text("PDF Compression")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        com.example.data.model.CompressionPreset.values().forEach { preset ->
                            FilterChip(
                                selected = uiState.selectedCompression == preset,
                                onClick = { viewModel.setCompression(preset) },
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
                                selected = uiState.ocrLanguage == language,
                                onClick = { viewModel.setOcrLanguage(language) },
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
                                viewModel.rotateEditingSessionPage(pagerState.currentPage, false)
                            } else {
                                viewModel.rotatePendingPage(pagerState.currentPage, false)
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
                                viewModel.rotateEditingSessionPage(pagerState.currentPage, true)
                            } else {
                                viewModel.rotatePendingPage(pagerState.currentPage, true)
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
