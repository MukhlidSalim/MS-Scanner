package com.example.ui.screens.idcard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.Emerald400
import com.example.ui.theme.StudioCanvasBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var isSideBySide by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(frontImagePath, backImagePath) {
        withContext(Dispatchers.IO) {
            frontBitmap = ImageProcessor.loadBitmapFromFile(frontImagePath, 1600)
            backBitmap = ImageProcessor.loadBitmapFromFile(backImagePath, 1600)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge ID Card", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            isSaving = true
                            coroutineScope.launch {
                                val mergedPath = ImageProcessor.createIdCardCollage(
                                    context = context,
                                    frontPath = frontImagePath,
                                    backPath = backImagePath,
                                    outPrefix = "id_merged_",
                                    isSideBySide = isSideBySide
                                )
                                isSaving = false
                                onMerged(mergedPath)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !isSideBySide,
                        onClick = { isSideBySide = false },
                        leadingIcon = { Icon(Icons.Default.ViewAgenda, contentDescription = null) },
                        label = { Text("Top & Bottom", fontWeight = FontWeight.SemiBold) }
                    )

                    FilterChip(
                        selected = isSideBySide,
                        onClick = { isSideBySide = true },
                        leadingIcon = { Icon(Icons.Default.ViewColumn, contentDescription = null) },
                        label = { Text("Side by Side", fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(StudioCanvasBg)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val front = frontBitmap
            val back = backBitmap

            if (front != null && back != null) {
                // A4 Sheet Preview Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f / 1.414f) // A4 ratio
                        .shadow(16.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isSideBySide) {
                        // Top & Bottom Layout
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceEvenly,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Front Card
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.LightGray),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .aspectRatio(1.58f)
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = front.asImageBitmap(),
                                    contentDescription = "ID Front",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Back Card
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.LightGray),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .aspectRatio(1.58f)
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = back.asImageBitmap(),
                                    contentDescription = "ID Back",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        // Side by Side Layout
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Front Card
                            Card(
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color.LightGray),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                                    .aspectRatio(1.58f)
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = front.asImageBitmap(),
                                    contentDescription = "ID Front",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Back Card
                            Card(
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color.LightGray),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp)
                                    .aspectRatio(1.58f)
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = back.asImageBitmap(),
                                    contentDescription = "ID Back",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(color = Emerald400)
            }
        }
    }
}
