package com.example.ui.screens.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.PageEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReorderPagesScreen(
    pages: List<PageEntity>,
    onSave: (List<PageEntity>) -> Unit,
    onCancel: () -> Unit
) {
    var items by remember { mutableStateOf(pages) }
    val gridState = rememberLazyGridState()
    
    // State for dragging
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder Pages", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Button(
                        onClick = { onSave(items) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "Long-press and drag any page to change order",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, page ->
                    val isDragging = index == draggingIndex
                    
                    Card(
                        modifier = Modifier
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isDragging) {
                                    Modifier.graphicsLayer {
                                        translationX = draggingOffset.x
                                        translationY = draggingOffset.y
                                        scaleX = 1.1f
                                        scaleY = 1.1f
                                        alpha = 0.85f
                                    }
                                } else {
                                    Modifier.animateItem()
                                }
                            )
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { _ ->
                                        draggingIndex = index
                                        draggingOffset = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffset += dragAmount
                                        
                                        val cellWidth = size.width
                                        val cellHeight = size.height
                                        
                                        var newIdx = index
                                        if (draggingOffset.x > cellWidth / 2) newIdx += 1
                                        if (draggingOffset.x < -cellWidth / 2) newIdx -= 1
                                        if (draggingOffset.y > cellHeight / 2) newIdx += 3
                                        if (draggingOffset.y < -cellHeight / 2) newIdx -= 3
                                        
                                        newIdx = newIdx.coerceIn(0, items.size - 1)
                                        
                                        if (newIdx != index && newIdx != draggingIndex) {
                                            val mutableList = items.toMutableList()
                                            val draggedItem = mutableList.removeAt(draggingIndex!!)
                                            mutableList.add(newIdx, draggedItem)
                                            items = mutableList
                                            draggingIndex = newIdx
                                            draggingOffset = androidx.compose.ui.geometry.Offset.Zero
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        draggingOffset = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        draggingOffset = androidx.compose.ui.geometry.Offset.Zero
                                    }
                                )
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            width = if (isDragging) 2.dp else 1.dp,
                            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = page.processedImagePath,
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.75f)
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
