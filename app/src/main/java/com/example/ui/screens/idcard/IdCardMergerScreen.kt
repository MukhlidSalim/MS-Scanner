package com.example.ui.screens.idcard

import androidx.compose.ui.res.stringResource
import com.example.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.engine.cv.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardMergerScreen(
    frontImagePath: String,
    backImagePath: String,
    onMerged: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    var frontOffset by remember { mutableStateOf(Offset(50f, 100f)) }
    var frontScale by remember { mutableStateOf(1f) }
    
    var backOffset by remember { mutableStateOf(Offset(50f, 600f)) }
    var backScale by remember { mutableStateOf(1f) }
    
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            frontBitmap = BitmapFactory.decodeFile(frontImagePath)
            backBitmap = BitmapFactory.decodeFile(backImagePath)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_merge_id_card)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                },
                actions = {
                    IconButton(onClick = {
                        if (frontBitmap != null && backBitmap != null) {
                            isSaving = true
                            coroutineScope.launch {
                                val resultPath = withContext(Dispatchers.IO) {
                                    // A4 size @ 300dpi is ~ 2480x3508. We'll use 1240x1754 for memory safety
                                    val outBitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                                    val canvas = Canvas(outBitmap)
                                    canvas.drawColor(android.graphics.Color.WHITE)
                                    
                                    val paint = Paint().apply { isFilterBitmap = true }
                                    
                                    // Draw Front
                                    canvas.save()
                                    canvas.translate(frontOffset.x, frontOffset.y)
                                    canvas.scale(frontScale, frontScale)
                                    canvas.drawBitmap(frontBitmap!!, 0f, 0f, paint)
                                    canvas.restore()
                                    
                                    // Draw Back
                                    canvas.save()
                                    canvas.translate(backOffset.x, backOffset.y)
                                    canvas.scale(backScale, backScale)
                                    canvas.drawBitmap(backBitmap!!, 0f, 0f, paint)
                                    canvas.restore()
                                    
                                    ImageProcessor.saveBitmapToFile(context, outBitmap, "id_merged_")
                                }
                                onMerged(resultPath)
                            }
                        }
                    }) {
                        if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        else Icon(Icons.Default.Check, "Save Merged")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            // A4 Canvas representation
            Box(
                modifier = Modifier
                    .size(width = 300.dp, height = 424.dp) // A4 ratio
                    .background(Color.White)
            ) {
                if (frontBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = frontBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.desc_front),
                        modifier = Modifier
                            .offset { IntOffset(frontOffset.x.roundToInt(), frontOffset.y.roundToInt()) }
                            .graphicsLayer(scaleX = frontScale, scaleY = frontScale)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    frontScale *= zoom
                                    frontOffset += pan
                                }
                            }
                    )
                }
                
                if (backBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = backBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.desc_back),
                        modifier = Modifier
                            .offset { IntOffset(backOffset.x.roundToInt(), backOffset.y.roundToInt()) }
                            .graphicsLayer(scaleX = backScale, scaleY = backScale)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    backScale *= zoom
                                    backOffset += pan
                                }
                            }
                    )
                }
            }
            
            Text(
                "Pinch to zoom, drag to move",
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                color = Color.DarkGray
            )
        }
    }
}
