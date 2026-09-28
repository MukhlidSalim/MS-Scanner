package com.example.ui.screens.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.ui.viewmodel.EditSessionViewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.ui.theme.Emerald400
import com.example.engine.cv.ImageProcessor
import com.example.data.model.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

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
    var pagesState by remember { mutableStateOf(pages) }
    LaunchedEffect(pages) { pagesState = pages }
    val haptic = LocalHapticFeedback.current
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }

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
    var showOriginalPreview by remember { mutableStateOf(false) }
    var contrast by remember { mutableStateOf(1f) }
    var isApplyingAdjustment by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = remember { Locale.getDefault().language == "ar" }

    val performSave = {
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
    }

    fun applyCurrentAdjustments() {
        val targetIndices = if (applyToAll) (0 until pages.size).toList() else listOf(pagerState.currentPage)
        isApplyingAdjustment = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                for (idx in targetIndices) {
                    val pageItem = pages.getOrNull(idx) ?: continue
                    val rawPath = when (pageItem) {
                        is com.example.data.model.PageEntity -> pageItem.rawImagePath.ifBlank { pageItem.processedImagePath }
                        is Pair<*, *> -> (pageItem as Pair<String, String>).first
                        else -> ""
                    }
                    if (rawPath.isNotEmpty()) {
                        val bmp = ImageProcessor.loadBitmapFromFile(rawPath)
                        if (bmp != null) {
                            val adjusted = ImageProcessor.adjustEnhancements(bmp, brightness * 50f, contrast, false)
                            val newPath = ImageProcessor.saveBitmapToFile(context, adjusted, "adj_proc_")
                            if (adjusted != bmp) adjusted.recycle()
                            bmp.recycle()

                            withContext(Dispatchers.Main) {
                                if (sourceType == "EXISTING") {
                                    editViewModel.updateEditingSessionPageProcessedImage(idx, newPath)
                                } else {
                                    onUpdatePendingPage(idx, newPath)
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    hasUnsavedChanges = true
                    isApplyingAdjustment = false
                    android.widget.Toast.makeText(
                        context,
                        if (isArabic) "تم تطبيق التعديلات بنجاح" else "Adjustments applied successfully",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isApplyingAdjustment = false
                }
            }
        }
    }

    fun applyQuickFilter(filter: FilterType) {
        val targetIndices = if (applyToAll) (0 until pages.size).toList() else listOf(pagerState.currentPage)
        isApplyingAdjustment = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                for (idx in targetIndices) {
                    val pageItem = pages.getOrNull(idx) ?: continue
                    val rawPath = when (pageItem) {
                        is com.example.data.model.PageEntity -> pageItem.rawImagePath.ifBlank { pageItem.processedImagePath }
                        is Pair<*, *> -> (pageItem as Pair<String, String>).first
                        else -> ""
                    }
                    if (rawPath.isNotEmpty()) {
                        val bmp = ImageProcessor.loadBitmapFromFile(rawPath)
                        if (bmp != null) {
                            val filtered = ImageProcessor.applyFilter(bmp, filter)
                            val newPath = ImageProcessor.saveBitmapToFile(context, filtered, "filter_proc_")
                            if (filtered != bmp) filtered.recycle()
                            bmp.recycle()

                            withContext(Dispatchers.Main) {
                                if (sourceType == "EXISTING") {
                                    editViewModel.updateEditingSessionPageProcessedImage(idx, newPath)
                                } else {
                                    onUpdatePendingPage(idx, newPath)
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    hasUnsavedChanges = true
                    isApplyingAdjustment = false
                    android.widget.Toast.makeText(
                        context,
                        if (isArabic) "تم تطبيق الفلتر بنجاح" else "Filter applied successfully",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isApplyingAdjustment = false
                }
            }
        }
    }

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
                        Text(if (isArabic) "مراجعة الصفحات ($current/${pages.size})" else "Review Pages ($current/${pages.size})") 
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
                    Text(if (isArabic) "لا توجد صفحات للعرض" else "No pages to display")
                }
            } else {
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    // Title Editor
                    if (sourceType != "EXISTING") {
                        OutlinedTextField(
                            value = documentTitle,
                            onValueChange = { documentTitle = it },
                            label = { Text(if (isArabic) "اسم المستند" else "Document Title") },
                            leadingIcon = { Icon(Icons.Default.Title, null) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            singleLine = true
                        )
                    }

                    // Main Image Pager
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    ) { pageIndex ->
                        val pageItem = pagesState.getOrNull(pageIndex)
                        val imagePath = when (pageItem) {
                            is com.example.data.model.PageEntity -> pageItem.processedImagePath
                            is Pair<*, *> -> (pageItem as Pair<String, String>).second
                            else -> ""
                        }
                        if (imagePath.isNotEmpty()) {
                            AsyncImage(
                                model = File(imagePath),
                                contentDescription = "Page ${pageIndex + 1}",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Reorderable Page List
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        itemsIndexed(pagesState) { index, page ->
                            val imagePath = when (page) {
                                is com.example.data.model.PageEntity -> page.processedImagePath
                                is Pair<*, *> -> (page as Pair<String, String>).second
                                else -> ""
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { 
                                        coroutineScope.launch { pagerState.scrollToPage(index) } 
                                    }
                                    .then(if (pagerState.currentPage == index) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .pointerInput(Unit) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggingItemIndex = index
                                            },
                                            onDragEnd = { draggingItemIndex = null },
                                            onDragCancel = { draggingItemIndex = null },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val targetIndex = (index + (dragAmount.x / 100).toInt()).coerceIn(0, pagesState.size - 1)
                                                if (targetIndex != index) {
                                                    val newPages = pagesState.toMutableList()
                                                    newPages.add(targetIndex, newPages.removeAt(index))
                                                    pagesState = newPages
                                                    draggingItemIndex = targetIndex
                                                }
                                            }
                                        )
                                    }
                            ) {
                                if (imagePath.isNotEmpty()) {
                                    AsyncImage(
                                        model = File(imagePath),
                                        contentDescription = "Page ${index + 1}",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Text(
                                    text = "${index + 1}",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp),
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Page Editor Toolbar (Rotate & Crop)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rotate Left
                        IconButton(onClick = {
                            val currentIndex = pagerState.currentPage
                            if (sourceType == "EXISTING") {
                                editViewModel.rotateEditingSessionPage(currentIndex, false)
                            } else {
                                onRotatePendingPage(currentIndex, false)
                            }
                        }) {
                            Icon(Icons.Default.RotateLeft, contentDescription = if (isArabic) "تدوير لليسار" else "Rotate Left")
                        }
                        
                        // Crop
                        Button(
                            onClick = { showCropEditor = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isArabic) "قص" else "Crop")
                        }

                        // Rotate Right
                        IconButton(onClick = {
                            val currentIndex = pagerState.currentPage
                            if (sourceType == "EXISTING") {
                                editViewModel.rotateEditingSessionPage(currentIndex, true)
                            } else {
                                onRotatePendingPage(currentIndex, true)
                            }
                        }) {
                            Icon(Icons.Default.RotateRight, contentDescription = if (isArabic) "تدوير لليمين" else "Rotate Right")
                        }
                    }
                    
                    // Edit Controls
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Quick Filters
                        Text(
                            text = if (isArabic) "فلاتر الألوان السريعة" else "Quick Color Filters",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { applyQuickFilter(FilterType.AUTO) },
                                label = { Text(if (isArabic) "تلقائي" else "Auto") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { applyQuickFilter(FilterType.MAGIC) },
                                label = { Text(if (isArabic) "ألوان سحرية" else "Magic Color") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { applyQuickFilter(FilterType.DOCUMENT) },
                                label = { Text(if (isArabic) "مستند نقي" else "Clean") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { applyQuickFilter(FilterType.BLACK_WHITE) },
                                label = { Text(if (isArabic) "أبيض وأسود" else "B&W") }
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                            Text(if (isArabic) "تطبيق التعديلات على جميع الصفحات" else "Apply adjustments to all pages")
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (isArabic) "السطوع" else "Brightness")
                            Text(String.format(Locale.US, "%.0f%%", brightness * 100))
                        }
                        Slider(value = brightness, onValueChange = { brightness = it; hasUnsavedChanges = true }, valueRange = -1f..1f)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (isArabic) "التباين" else "Contrast")
                            Text(String.format(Locale.US, "%.1fx", contrast))
                        }
                        Slider(value = contrast, onValueChange = { contrast = it; hasUnsavedChanges = true }, valueRange = 0.5f..2.5f)

                        // Action buttons for sliders
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    brightness = 0f
                                    contrast = 1f
                                    applyCurrentAdjustments()
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isApplyingAdjustment
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isArabic) "إعادة تعيين" else "Reset")
                            }

                            Button(
                                onClick = { applyCurrentAdjustments() },
                                modifier = Modifier.weight(1.4f),
                                enabled = !isApplyingAdjustment
                            ) {
                                if (isApplyingAdjustment) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(if (isArabic) "تطبيق التعديلات" else "Apply")
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text(if (isArabic) "ضغط ملف الـ PDF" else "PDF Compression")
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

                        Text(if (isArabic) "لغة استخراج النص (OCR)" else "OCR Language")
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
                            // Smart Enhance
                            IconButton(onClick = {
                                editViewModel.smartEnhanceActivePage()
                            }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Smart Enhance")
                            }
                        }
                    }
                }
            }
        }
    }
}
