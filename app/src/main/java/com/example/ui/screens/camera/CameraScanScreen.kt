package com.example.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.ui.components.ScannerOverlay
import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight
import com.example.ui.viewmodel.ScanMode
import com.example.ui.viewmodel.ScanViewModel
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    onNavigateBack: () -> Unit,
    onDocumentCreated: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    fun capturePhoto() {
        val capture = imageCapture ?: return
        if (uiState.isProcessingCapture) return

        capture.takePicture(
            Executors.newSingleThreadExecutor(),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    if (bitmap != null) {
                        viewModel.onCaptureCompleted(bitmap) { docId ->
                            onDocumentCreated(docId)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    // ignore or log
                }
            }
        )
    }

    // Sync flash mode with CameraControl
    LaunchedEffect(uiState.flashMode, cameraControl) {
        val ctrl = cameraControl ?: return@LaunchedEffect
        when (uiState.flashMode) {
            1 -> ctrl.enableTorch(true)
            else -> ctrl.enableTorch(false)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()
                        imageCapture = capture

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        val analysisExecutor = Executors.newSingleThreadExecutor()
                        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val frameBitmap = imageProxyToBitmap(imageProxy)
                            imageProxy.close()
                            if (frameBitmap != null) {
                                viewModel.onFrameAnalyzed(frameBitmap) {
                                    capturePhoto()
                                }
                                frameBitmap.recycle()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                                imageAnalysis
                            )
                            cameraControl = cam.cameraControl
                        } catch (e: Exception) {
                            // Camera bind error
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Real-Time Augmented Viewfinder Overlay
            ScannerOverlay(
                detection = uiState.detection,
                isGridVisible = uiState.isGridVisible
            )

            // Top Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .testTag("scanner_back_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flash Mode Toggle
                    IconButton(
                        onClick = { viewModel.toggleFlash() },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .testTag("flash_toggle_btn")
                    ) {
                        val icon = when (uiState.flashMode) {
                            1 -> Icons.Default.FlashOn
                            2 -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        }
                        Icon(icon, contentDescription = "Flash", tint = if (uiState.flashMode > 0) EmeraldLight else Color.White)
                    }

                    // Grid Toggle
                    IconButton(
                        onClick = { viewModel.toggleGrid() },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .testTag("grid_toggle_btn")
                    ) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Grid",
                            tint = if (uiState.isGridVisible) EmeraldLight else Color.White
                        )
                    }

                    // Auto-Capture Toggle Pill
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { viewModel.toggleAutoCapture() }
                            .testTag("auto_capture_pill"),
                        color = if (uiState.isAutoCaptureEnabled) EmeraldLight.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isAutoCaptureEnabled) Icons.Default.AutoFixHigh else Icons.Default.AutoFixNormal,
                                contentDescription = null,
                                tint = if (uiState.isAutoCaptureEnabled) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (uiState.isAutoCaptureEnabled) "Auto" else "Manual",
                                color = if (uiState.isAutoCaptureEnabled) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ID Card Step Indicator Banner
            if (uiState.mode == ScanMode.ID_CARD) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 130.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = if (uiState.idCardStep == 0) "Step 1 of 2: Scan FRONT of ID Card" else "Step 2 of 2: Scan BACK of ID Card",
                        color = CyanScan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Bottom Controls Area
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Batch Staged Pages Tray
                AnimatedVisibility(visible = uiState.stagedPages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        uiState.stagedPages.forEachIndexed { index, page ->
                            Box(
                                modifier = Modifier
                                    .size(54.dp, 72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.5.dp, EmeraldLight, RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                            ) {
                                AsyncImage(
                                    model = File(page.processedPath),
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Delete mini button
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                        .clickable { viewModel.removeStagedPage(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove page",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Shutter & Actions Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left spacer or page counter
                    if (uiState.mode == ScanMode.BATCH && uiState.stagedPages.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = EmeraldLight
                        ) {
                            Text(
                                text = "${uiState.stagedPages.size}",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Main Shutter Button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .border(
                                4.dp,
                                if (uiState.detection.isStable) EmeraldLight else Color.White,
                                CircleShape
                            )
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(if (uiState.detection.isStable) EmeraldLight else Color.White)
                            .clickable(enabled = !uiState.isProcessingCapture) {
                                capturePhoto()
                            }
                            .testTag("shutter_capture_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.isProcessingCapture) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.Black,
                                strokeWidth = 3.dp
                            )
                        }
                    }

                    // Done / Finish button (For Batch mode)
                    if (uiState.mode == ScanMode.BATCH && uiState.stagedPages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.finishBatchScanning { docId ->
                                    onDocumentCreated(docId)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(EmeraldLight, CircleShape)
                                .testTag("batch_done_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.Black)
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }

                // Scan Mode Selector Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScanMode.values().forEach { mode ->
                        val isSelected = mode == uiState.mode
                        val label = when (mode) {
                            ScanMode.SINGLE -> "Single"
                            ScanMode.BATCH -> "Batch"
                            ScanMode.ID_CARD -> "ID Card"
                            ScanMode.RECEIPT -> "Receipt"
                        }
                        Text(
                            text = label,
                            color = if (isSelected) EmeraldLight else Color.LightGray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setScanMode(mode) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .testTag("scan_mode_${mode.name}")
                        )
                    }
                }
            }
        } else {
            // Permission Denied UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = EmeraldLight,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Permission Required",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DocScan Pro requires camera access to detect documents, correct perspective, and scan pages.",
                    color = Color.LightGray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("grant_camera_permission_btn")
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    return try {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val rotation = image.imageInfo.rotationDegrees
        val bitmap = if (image.format == android.graphics.ImageFormat.JPEG) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            // RGBA_8888
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            bmp
        }

        if (bitmap != null && rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        null
    }
}
