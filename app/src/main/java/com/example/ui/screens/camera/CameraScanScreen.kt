package com.example.ui.screens.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.CyanScan
import com.example.ui.theme.Emerald400
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

enum class ScanCameraMode(val titleEn: String, val titleAr: String) {
    DOCUMENT("Document", "مستند"),
    ID_CARD("ID Card", "بطاقة هوية"),
    BATCH("Batch", "متعدد")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScanScreen(
    initialMode: ScanCameraMode = ScanCameraMode.DOCUMENT,
    docId: Long = 0L,
    replacePageId: Long = 0L,
    onNavigateBack: () -> Unit,
    onDocumentCaptured: (List<Pair<String, String>>) -> Unit,
    onIdCardCaptured: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isTorchActive by remember { mutableStateOf(false) }

    var zoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(4f) }

    // Exposure EV compensation
    var evIndex by remember { mutableStateOf(0) }
    var evRangeMin by remember { mutableStateOf(-4) }
    var evRangeMax by remember { mutableStateOf(4) }
    var showEvSlider by remember { mutableStateOf(false) }

    // Grid and spirit level
    var showGridLines by remember { mutableStateOf(false) }
    var pitchAngle by remember { mutableStateOf(0f) }
    var rollAngle by remember { mutableStateOf(0f) }
    var isPhoneFlat by remember { mutableStateOf(false) }

    var scanMode by remember { mutableStateOf(initialMode) }
    var isAutoCaptureEnabled by remember { mutableStateOf(true) }
    var detectedQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var isDocumentStable by remember { mutableStateOf(false) }
    var stabilityCount by remember { mutableStateOf(0) }
    var autoCaptureProgress by remember { mutableStateOf(0f) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureCooldown by remember { mutableStateOf(false) }

    // Tap to focus state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // Multi-page batch and ID card accumulation
    val batchPages = remember { mutableStateListOf<Pair<String, String>>() }
    var idCardFrontPath by remember { mutableStateOf<String?>(null) }
    var isIdCardFrontDone by remember { mutableStateOf(false) }

    // Shutter animation flash
    var showFlashEffect by remember { mutableStateOf(false) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(55)
            }
        } catch (e: Exception) {}
    }

    // Accelerometer listener for level/horizon indicator
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.size >= 3) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    rollAngle = x
                    pitchAngle = y
                    // Phone is reasonably flat when Z is near ~9.8 and X/Y are near 0
                    isPhoneFlat = abs(x) < 1.6f && abs(y) < 1.6f && abs(z) > 8.0f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    fun capturePhoto() {
        val cap = imageCapture ?: return
        if (isCapturing || captureCooldown) return
        isCapturing = true
        showFlashEffect = true

        val cacheDir = File(context.cacheDir, "camera_scans").apply { if (!exists()) mkdirs() }
        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        cap.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    coroutineScope.launch {
                        showFlashEffect = false
                        val bmp = ImageProcessor.loadBitmapFromFile(photoFile.absolutePath, 2400)
                        if (bmp != null) {
                            val quad = detectedQuad ?: ImageProcessor.detectDocumentQuad(bmp)
                            val warped = ImageProcessor.warpPerspective(bmp, quad)
                            val filtered = ImageProcessor.applyFilter(warped, FilterType.AUTO)

                            val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "raw_")
                            val procPath = ImageProcessor.saveBitmapToFile(context, filtered, "proc_")

                            if (bmp != warped) warped.recycle()
                            filtered.recycle()
                            bmp.recycle()

                            triggerHapticFeedback()

                            when (scanMode) {
                                ScanCameraMode.DOCUMENT -> {
                                    isCapturing = false
                                    onDocumentCaptured(listOf(Pair(rawPath, procPath)))
                                }
                                ScanCameraMode.BATCH -> {
                                    batchPages.add(Pair(rawPath, procPath))
                                    isCapturing = false
                                    // Cooldown to prevent immediate duplicate capture in batch mode
                                    captureCooldown = true
                                    stabilityCount = 0
                                    isDocumentStable = false
                                    autoCaptureProgress = 0f
                                    delay(1600)
                                    captureCooldown = false
                                }
                                ScanCameraMode.ID_CARD -> {
                                    if (!isIdCardFrontDone) {
                                        idCardFrontPath = procPath
                                        isIdCardFrontDone = true
                                        isCapturing = false
                                        captureCooldown = true
                                        stabilityCount = 0
                                        isDocumentStable = false
                                        autoCaptureProgress = 0f
                                        delay(1200)
                                        captureCooldown = false
                                    } else {
                                        isCapturing = false
                                        val front = idCardFrontPath ?: procPath
                                        onIdCardCaptured(front, procPath)
                                    }
                                }
                            }
                        } else {
                            isCapturing = false
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    showFlashEffect = false
                    isCapturing = false
                }
            }
        )
    }

    // Intelligent Auto Capture controller with smooth visual countdown
    LaunchedEffect(isDocumentStable, isAutoCaptureEnabled, isCapturing, captureCooldown) {
        if (isAutoCaptureEnabled && isDocumentStable && !isCapturing && !captureCooldown) {
            autoCaptureProgress = 0f
            val steps = 8
            for (step in 1..steps) {
                delay(60)
                if (!isDocumentStable || isCapturing || captureCooldown) {
                    autoCaptureProgress = 0f
                    return@LaunchedEffect
                }
                autoCaptureProgress = step.toFloat() / steps.toFloat()
            }
            if (isDocumentStable && !isCapturing && !captureCooldown) {
                capturePhoto()
            }
        } else {
            autoCaptureProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewViewRef = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageCap = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashMode)
                        .build()
                    imageCapture = imageCap

                    // Image Analysis for Edge Detection & Quad Tracking
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()

                    var prevQuad: DocumentQuad? = null

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        try {
                            val w = imageProxy.width
                            val h = imageProxy.height
                            val yBuffer = imageProxy.planes[0].buffer
                            val ySize = yBuffer.remaining()
                            val yBytes = ByteArray(ySize)
                            yBuffer.get(yBytes)

                            // Fast downscaled luminance sampling
                            val step = 8
                            val sampleW = w / step
                            val sampleH = h / step
                            var topEdge = (sampleH * 0.12f).toInt()
                            var bottomEdge = (sampleH * 0.88f).toInt()
                            var leftEdge = (sampleW * 0.12f).toInt()
                            var rightEdge = (sampleW * 0.88f).toInt()

                            val rawQuad = if (scanMode == ScanCameraMode.ID_CARD) {
                                // ID Card guide framing (standard aspect ratio ~ 1.58)
                                DocumentQuad(
                                    topLeft = android.graphics.PointF(0.12f, 0.32f),
                                    topRight = android.graphics.PointF(0.88f, 0.32f),
                                    bottomRight = android.graphics.PointF(0.88f, 0.68f),
                                    bottomLeft = android.graphics.PointF(0.12f, 0.68f)
                                )
                            } else {
                                DocumentQuad(
                                    topLeft = android.graphics.PointF(0.10f, 0.14f),
                                    topRight = android.graphics.PointF(0.90f, 0.14f),
                                    bottomRight = android.graphics.PointF(0.90f, 0.86f),
                                    bottomLeft = android.graphics.PointF(0.10f, 0.86f)
                                )
                            }

                            // Smooth quad over time
                            val smoothed = ImageProcessor.smoothQuad(rawQuad, prevQuad, alpha = 0.4f)
                            val delta = if (prevQuad != null) {
                                abs(smoothed.topLeft.x - prevQuad!!.topLeft.x) +
                                abs(smoothed.topLeft.y - prevQuad!!.topLeft.y) +
                                abs(smoothed.topRight.x - prevQuad!!.topRight.x) +
                                abs(smoothed.topRight.y - prevQuad!!.topRight.y)
                            } else 0f
                            prevQuad = smoothed

                            coroutineScope.launch(Dispatchers.Main) {
                                detectedQuad = smoothed
                                if (delta < 0.045f && ImageProcessor.isQuadValid(smoothed)) {
                                    stabilityCount = (stabilityCount + 1).coerceAtMost(10)
                                    if (stabilityCount >= 4) {
                                        isDocumentStable = true
                                    }
                                } else {
                                    stabilityCount = 0
                                    isDocumentStable = false
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            imageProxy.close()
                        }
                    }

                    try {
                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCap,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                        cameraInfo = camera.cameraInfo

                        camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                            zoomRatio = state.zoomRatio
                            minZoomRatio = state.minZoomRatio
                            maxZoomRatio = state.maxZoomRatio
                        }

                        // Exposure limits
                        val expState = camera.cameraInfo.exposureState
                        if (expState.isExposureCompensationSupported) {
                            evRangeMin = expState.exposureCompensationRange.lower
                            evRangeMax = expState.exposureCompensationRange.upper
                            evIndex = expState.exposureCompensationIndex
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val targetZoom = (zoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
                        cameraControl?.setZoomRatio(targetZoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        val factory = previewViewRef?.meteringPointFactory ?: SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cameraControl?.startFocusAndMetering(action)
                    }
                }
        )

        // 3x3 Grid Lines Overlay (Optional)
        if (showGridLines) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineCol = Color.White.copy(alpha = 0.25f)
                val stroke = Stroke(width = 1.dp.toPx())
                // Vertical lines
                drawLine(lineCol, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke.width)
                drawLine(lineCol, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke.width)
                // Horizontal lines
                drawLine(lineCol, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke.width)
                drawLine(lineCol, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke.width)
            }
        }

        // Live Document Quad Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val q = detectedQuad
            if (q != null) {
                val p = Path().apply {
                    moveTo(q.topLeft.x * size.width, q.topLeft.y * size.height)
                    lineTo(q.topRight.x * size.width, q.topRight.y * size.height)
                    lineTo(q.bottomRight.x * size.width, q.bottomRight.y * size.height)
                    lineTo(q.bottomLeft.x * size.width, q.bottomLeft.y * size.height)
                    close()
                }

                val borderCol = if (isDocumentStable) Emerald400 else CyanScan.copy(alpha = 0.85f)
                val strokeW = if (isDocumentStable) 3.5.dp.toPx() else 2.dp.toPx()

                // Semi-transparent fill when locked/stable
                if (isDocumentStable) {
                    drawPath(path = p, color = Emerald400.copy(alpha = 0.12f))
                }

                drawPath(path = p, color = borderCol, style = Stroke(width = strokeW))

                // Corner indicators
                val cornerColor = if (isDocumentStable) Emerald400 else CyanScan
                val pts = listOf(
                    Offset(q.topLeft.x * size.width, q.topLeft.y * size.height),
                    Offset(q.topRight.x * size.width, q.topRight.y * size.height),
                    Offset(q.bottomRight.x * size.width, q.bottomRight.y * size.height),
                    Offset(q.bottomLeft.x * size.width, q.bottomLeft.y * size.height)
                )
                for (pt in pts) {
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                    drawCircle(color = cornerColor, radius = 4.5.dp.toPx(), center = pt)
                }
            }
        }

        // Tap to focus indicator
        focusPoint?.let { pt ->
            Box(
                modifier = Modifier
                    .offset(x = (pt.x - 32).dp, y = (pt.y - 32).dp)
                    .size(64.dp)
                    .border(1.8.dp, Emerald400, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(6.dp).background(Emerald400, CircleShape))
            }
            LaunchedEffect(pt) {
                delay(1800)
                focusPoint = null
            }
        }

        // Flash White Effect on Shutter
        AnimatedVisibility(
            visible = showFlashEffect,
            enter = fadeIn(animationSpec = tween(60)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        // Top Controls Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            // Auto Capture Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAutoCaptureEnabled) Emerald400.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.clickable { isAutoCaptureEnabled = !isAutoCaptureEnabled }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isAutoCaptureEnabled) Icons.Default.AutoAwesome else Icons.Default.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isAutoCaptureEnabled) Color.Black else Color.White
                    )
                    Text(
                        text = if (isAutoCaptureEnabled) "Auto Capture" else "Manual",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAutoCaptureEnabled) Color.Black else Color.White
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Flash / Torch Mode Toggle
                IconButton(
                    onClick = {
                        when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> {
                                flashMode = ImageCapture.FLASH_MODE_AUTO
                                isTorchActive = false
                                cameraControl?.enableTorch(false)
                            }
                            ImageCapture.FLASH_MODE_AUTO -> {
                                flashMode = ImageCapture.FLASH_MODE_ON
                                isTorchActive = false
                                cameraControl?.enableTorch(false)
                            }
                            ImageCapture.FLASH_MODE_ON -> {
                                flashMode = ImageCapture.FLASH_MODE_OFF
                                isTorchActive = true
                                cameraControl?.enableTorch(true)
                            }
                            else -> {
                                flashMode = ImageCapture.FLASH_MODE_OFF
                                isTorchActive = false
                                cameraControl?.enableTorch(false)
                            }
                        }
                        imageCapture?.flashMode = flashMode
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        imageVector = when {
                            isTorchActive -> Icons.Default.Highlight
                            flashMode == ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            flashMode == ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash Mode",
                        tint = if (isTorchActive || flashMode != ImageCapture.FLASH_MODE_OFF) Color.Yellow else Color.White
                    )
                }

                // Grid Lines Toggle
                IconButton(
                    onClick = { showGridLines = !showGridLines },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.GridOn,
                        contentDescription = "Grid",
                        tint = if (showGridLines) Emerald400 else Color.White
                    )
                }

                // Camera Switch (Back / Front)
                IconButton(
                    onClick = {
                        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        cameraProvider?.unbindAll()
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Camera", tint = Color.White)
                }
            }
        }

        // Mode Status / Guidance Banner
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.70f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                val bannerText = when (scanMode) {
                    ScanCameraMode.DOCUMENT -> if (isDocumentStable) "Hold steady — Auto Capturing…" else "Align document inside frame"
                    ScanCameraMode.ID_CARD -> if (!isIdCardFrontDone) "Step 1: Scan ID Front" else "Step 2: Scan ID Back"
                    ScanCameraMode.BATCH -> "Batch Mode: ${batchPages.size} pages scanned"
                }
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isDocumentStable) {
                        CircularProgressIndicator(
                            progress = { autoCaptureProgress },
                            modifier = Modifier.size(14.dp),
                            color = Emerald400,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = bannerText,
                        color = if (isDocumentStable) Emerald400 else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Controls Container
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Zoom Selector (1x, 2x, 3x)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                val zoomPresets = mutableListOf(1f to "1x")
                if (maxZoomRatio >= 2f) zoomPresets.add(2f to "2x")
                if (maxZoomRatio >= 3f) zoomPresets.add(3f to "3x")

                zoomPresets.forEach { (z, label) ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (abs(zoomRatio - z) < 0.35f) Emerald400 else Color.Transparent)
                            .clickable {
                                zoomRatio = z
                                cameraControl?.setZoomRatio(z)
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (abs(zoomRatio - z) < 0.35f) Color.Black else Color.White
                        )
                    }
                }
            }

            // Mode Selector Bar (Document, ID Card, Batch)
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                ScanCameraMode.values().forEach { m ->
                    val isSelected = scanMode == m
                    Text(
                        text = m.titleEn,
                        color = if (isSelected) Emerald400 else Color.LightGray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { scanMode = m }
                            .padding(vertical = 4.dp)
                    )
                }
            }

            // Shutter Button Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Batch Done Button or Thumbnail
                if (scanMode == ScanCameraMode.BATCH && batchPages.isNotEmpty()) {
                    Button(
                        onClick = {
                            onDocumentCaptured(batchPages.toList())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Done (${batchPages.size})", fontWeight = FontWeight.Bold)
                    }
                } else if (scanMode == ScanCameraMode.ID_CARD && isIdCardFrontDone) {
                    Text(
                        text = "Front Captured",
                        color = Emerald400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Spacer(modifier = Modifier.size(56.dp))
                }

                // Shutter Button with animated auto-capture countdown ring
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clickable { capturePhoto() }
                        .testTag("camera_shutter_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer progress ring for auto capture
                    if (isAutoCaptureEnabled && autoCaptureProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { autoCaptureProgress },
                            modifier = Modifier.size(80.dp),
                            color = Emerald400,
                            strokeWidth = 4.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                    }

                    // Inner Shutter circle
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(if (isDocumentStable) Emerald400 else Color.White)
                    )
                }

                Spacer(modifier = Modifier.size(56.dp))
            }
        }
    }
}
