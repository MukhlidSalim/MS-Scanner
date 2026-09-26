package com.example.ui.screens.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.ui.viewmodel.DocumentViewModel
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditSessionScreen(
    viewModel: DocumentViewModel,
    docId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToFinish: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val pages: List<Any> = if (uiState.isEditingSession) uiState.editingSessionPages else uiState.pendingPages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    
    var applyToAll by remember { mutableStateOf(false) }
    // State for common edits
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }

    // Logic to update page based on 'applyToAll'
    LaunchedEffect(brightness, contrast) {
        if (applyToAll) {
            // Apply to all pages
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Pages (${pagerState.currentPage + 1}/${pages.size})") },
                actions = {
                    TextButton(onClick = { 
                        viewModel.clearPendingPages()
                        onNavigateBack() 
                    }) { Text("Cancel") }
                    Button(onClick = {
                        if (uiState.isEditingSession) {
                            viewModel.commitEditingSessionChanges {
                                onNavigateToFinish()
                            }
                        } else {
                            viewModel.commitPendingPagesToDocument(docId) {
                                onNavigateToFinish()
                            }
                        }
                    }) {
                        Text("Save All")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = File(
                            if (uiState.isEditingSession) {
                                (pages[pageIndex] as com.example.data.model.PageEntity).processedImagePath
                            } else {
                                (pages[pageIndex] as Pair<String, String>).second
                            }
                        ),
                        contentDescription = "Page ${pageIndex + 1}",
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
            
            // Edit Controls
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = {
                        if (uiState.isEditingSession) viewModel.rotateEditingSessionPage(pagerState.currentPage, false)
                        else viewModel.rotatePendingPage(pagerState.currentPage, false)
                    }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate Left")
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    IconButton(onClick = {
                        if (uiState.isEditingSession) viewModel.rotateEditingSessionPage(pagerState.currentPage, true)
                        else viewModel.rotatePendingPage(pagerState.currentPage, true)
                    }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate Right")
                    }
                }
            }
        }
    }
}
