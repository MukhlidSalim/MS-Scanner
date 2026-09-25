package com.example.ui.screens.viewer

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.PageEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReorderPagesScreen(
    pages: List<PageEntity>,
    onSave: (List<PageEntity>) -> Unit,
    onCancel: () -> Unit
) {
    var items by remember { mutableStateOf(pages) }
    val gridState = rememberLazyGridState()
    
    // Simple state for dragging
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder Pages") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(items) }) {
                        Icon(Icons.Default.Check, "Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, page ->
                val isDragging = index == draggingIndex
                
                Box(
                    modifier = Modifier
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (isDragging) {
                                Modifier.graphicsLayer {
                                    translationX = draggingOffset.x
                                    translationY = draggingOffset.y
                                    scaleX = 1.1f
                                    scaleY = 1.1f
                                    alpha = 0.8f
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
                                    
                                    // Basic collision detection with other items
                                    // In a real robust implementation, we would calculate bounds.
                                    // Here we just do a simplistic approach: if moved enough, swap.
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
                        }
                ) {
                    AsyncImage(
                        model = page.processedImagePath,
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
