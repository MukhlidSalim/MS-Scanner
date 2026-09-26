package com.example.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.*
import com.example.ui.screens.camera.components.PermissionRationaleScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

enum class ScanCameraMode(val titleEn: String, val titleAr: String, val shortTitleAr: String) {
    DOCUMENT("Document", "تصوير", "تصوير"),
    BATCH("Multi-Page", "تصوير متعدد", "متعدد"),
    ID_CARD("ID Card", "تصوير بطاقة", "بطاقة"),
    PASSPORT("Passport", "تصوير جواز", "جواز")
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
    onPassportCaptured: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"

    // Camera runtime permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(
                context,
                if (isArabic) "إذن الكاميرا مطلوب لمسح المستندات" else "Camera permission required to scan",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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

    // Grid and spirit level
    var showGridLines by remember { mutableStateOf(false) }
    var pitchAngle by remember { mutableStateOf(0f) }
    var rollAngle by remember { mutableStateOf(0f) }
    var isPhoneFlat by remember { mutableStateOf(false) }

    var scanMode by remember { mutableStateOf(initialMode) }
    var isAutoCaptureEnabled by remember { mutableStateOf(true) }

    // Automatically activate auto-capture whenever in document scanning mode
    LaunchedEffect(scanMode) {
        if (scanMode == ScanCameraMode.DOCUMENT) {
            isAutoCaptureEnabled = true
        }
    }
    var detectedQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var isDocumentStable by remember { mutableStateOf(false) }
    var stabilityCount by remember { mutableStateOf(0) }
    var autoCaptureProgress by remember { mutableStateOf(0f) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureCooldown by remember { mutableStateOf(false) }

    // Tap to focus state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // Multi-page batch, ID card and passport accumulation
    val batchPages = remember { mutableStateListOf<Pair<String, String>>() }
    var idCardFrontPath by remember { mutableStateOf<String?>(null) }
    var isIdCardFrontDone by remember { mutableStateOf(false) }
    var passportFrontPath by remember { mutableStateOf<String?>(null) }
    var isPassportFrontDone by remember { mutableStateOf(false) }

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

    // Gallery Picker as instant fallback & import option
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isCapturing = true
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) {
                        val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "import_raw_")
                        val quad = ImageProcessor.detectDocumentQuad(bmp)
                        val procBmp = try {
                            val warped = ImageProcessor.warpPerspective(bmp, quad)
                            val filtered = ImageProcessor.applyFilter(warped, FilterType.AUTO)
                            if (warped != bmp && warped != filtered) warped.recycle()
                            filtered
                        } catch (e: Exception) {
                            ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                        }
                        val procPath = ImageProcessor.saveBitmapToFile(context, procBmp, "import_proc_")
                        if (procBmp != bmp) procBmp.recycle()
                        bmp.recycle()

                        triggerHapticFeedback()
                        when (scanMode) {
                            ScanCameraMode.DOCUMENT -> {
                                onDocumentCaptured(listOf(Pair(rawPath, procPath)))
                            }
                            ScanCameraMode.PASSPORT -> {
                                if (!isPassportFrontDone) {
                                    passportFrontPath = procPath
                                    isPassportFrontDone = true
                                } else {
                                    val front = passportFrontPath ?: procPath
                                    onPassportCaptured?.invoke(front, procPath) ?: onIdCardCaptured(front, procPath)
                                }
                            }
                            ScanCameraMode.BATCH -> {
                                batchPages.add(Pair(rawPath, procPath))
                            }
                            ScanCameraMode.ID_CARD -> {
                                if (!isIdCardFrontDone) {
                                    idCardFrontPath = procPath
                                    isIdCardFrontDone = true
                                } else {
                                    val front = idCardFrontPath ?: procPath
                                    onIdCardCaptured(front, procPath)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, if (isArabic) "فشل استيراد الصورة" else "Failed to import image", Toast.LENGTH_SHORT).show()
                } finally {
                    isCapturing = false
                }
            }
        }
    }

    // System Camera fallback launcher
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraFile by remember { mutableStateOf<File?>(null) }
    val systemCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempCameraFile
        if (success && file != null && file.exists()) {
            coroutineScope.launch {
                isCapturing = true
                try {
                    val exif = ExifInterface(file.absolutePath)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    val rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                    var bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        if (rotationDegrees != 0) {
                            val rotated = ImageProcessor.rotateBitmap(bmp, rotationDegrees)
                            if (rotated != bmp) {
                                bmp.recycle()
                                bmp = rotated
                            }
                        }
                        val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "cam_raw_")
                        val quad = ImageProcessor.detectDocumentQuad(bmp)
                        val procBmp = try {
                            val warped = ImageProcessor.warpPerspective(bmp, quad)
                            val filtered = ImageProcessor.applyFilter(warped, FilterType.AUTO)
                            if (warped != bmp && warped != filtered) warped.recycle()
                            filtered
                        } catch (e: Exception) {
                            ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                        }
                        val procPath = ImageProcessor.saveBitmapToFile(context, procBmp, "cam_proc_")
                        if (procBmp != bmp) procBmp.recycle()
                        bmp.recycle()

                        triggerHapticFeedback()
                        when (scanMode) {
                            ScanCameraMode.DOCUMENT -> {
                                onDocumentCaptured(listOf(Pair(rawPath, procPath)))
                            }
                            ScanCameraMode.PASSPORT -> {
                                if (!isPassportFrontDone) {
                                    passportFrontPath = procPath
                                    isPassportFrontDone = true
                                } else {
                                    val front = passportFrontPath ?: procPath
                                    onPassportCaptured?.invoke(front, procPath) ?: onIdCardCaptured(front, procPath)
                                }
                            }
                            ScanCameraMode.BATCH -> {
                                batchPages.add(Pair(rawPath, procPath))
                            }
                            ScanCameraMode.ID_CARD -> {
                                if (!isIdCardFrontDone) {
                                    idCardFrontPath = procPath
                                    isIdCardFrontDone = true
                                } else {
                                    val front = idCardFrontPath ?: procPath
                                    onIdCardCaptured(front, procPath)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isCapturing = false
                }
            }
        }
    }

    val launchSystemCamera = {
        try {
            val dir = File(context.cacheDir, "camera_scans").apply { if (!exists()) mkdirs() }
            val file = File(dir, "sys_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            tempCameraUri = uri
            systemCameraLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Reactive CameraX binding with robust fallback
    LaunchedEffect(hasCameraPermission, cameraSelector, previewViewRef) {
        val pView = previewViewRef
        if (!hasCameraPermission || pView == null) return@LaunchedEffect

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pView.surfaceProvider)
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
                var prevSamples: FloatArray? = null
                var frameIndex = 0

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    try {
                        val currentMode = scanMode
                        val rawQuad = when (currentMode) {
                            ScanCameraMode.ID_CARD -> {
                                DocumentQuad(
                                    topLeft = android.graphics.PointF(0.10f, 0.30f),
                                    topRight = android.graphics.PointF(0.90f, 0.30f),
                                    bottomRight = android.graphics.PointF(0.90f, 0.70f),
                                    bottomLeft = android.graphics.PointF(0.10f, 0.70f)
                                )
                            }
                            ScanCameraMode.PASSPORT -> {
                                DocumentQuad(
                                    topLeft = android.graphics.PointF(0.08f, 0.20f),
                                    topRight = android.graphics.PointF(0.92f, 0.20f),
                                    bottomRight = android.graphics.PointF(0.92f, 0.80f),
                                    bottomLeft = android.graphics.PointF(0.08f, 0.80f)
                                )
                            }
                            else -> {
                                DocumentQuad(
                                    topLeft = android.graphics.PointF(0.08f, 0.12f),
                                    topRight = android.graphics.PointF(0.92f, 0.12f),
                                    bottomRight = android.graphics.PointF(0.92f, 0.88f),
                                    bottomLeft = android.graphics.PointF(0.08f, 0.88f)
                                )
                            }
                        }

                        val smoothed = ImageProcessor.smoothQuad(rawQuad, prevQuad, alpha = 0.4f)
                        prevQuad = smoothed

                        // Sample luminance to calculate phone stability and camera motion
                        val yPlane = imageProxy.planes.firstOrNull()
                        var isSteady = false
                        if (yPlane != null) {
                            val buffer = yPlane.buffer
                            val rowStride = yPlane.rowStride
                            val w = imageProxy.width
                            val h = imageProxy.height
                            val grid = 6
                            val samples = FloatArray(grid * grid)
                            var diffSum = 0f
                            var validSamples = 0
                            val bufCap = buffer.capacity()

                            for (gy in 0 until grid) {
                                val py = (h * (gy + 1)) / (grid + 1)
                                val rowStart = py * rowStride
                                for (gx in 0 until grid) {
                                    val px = (w * (gx + 1)) / (grid + 1)
                                    val pos = rowStart + px
                                    if (pos < bufCap) {
                                        val luma = (buffer.get(pos).toInt() and 0xFF).toFloat()
                                        val idx = gy * grid + gx
                                        samples[idx] = luma
                                        val prev = prevSamples
                                        if (prev != null) {
                                            diffSum += abs(luma - prev[idx])
                                            validSamples++
                                        }
                                    }
                                }
                            }
                            if (prevSamples != null && validSamples > 0) {
                                val avgDiff = diffSum / validSamples
                                // If camera motion is small, phone is held steady on document
                                isSteady = avgDiff < 8.0f
                            }
                            prevSamples = samples
                        } else {
                            isSteady = true
                        }

                        frameIndex++
                        coroutineScope.launch(Dispatchers.Main) {
                            detectedQuad = smoothed
                            if (isSteady && frameIndex > 8) {
                                stabilityCount = (stabilityCount + 1).coerceAtMost(15)
                                if (stabilityCount >= 5) {
                                    isDocumentStable = true
                                }
                            } else {
                                stabilityCount = (stabilityCount - 1).coerceAtLeast(0)
                                if (stabilityCount == 0) {
                                    isDocumentStable = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        imageProxy.close()
                    }
                }

                var camera: Camera? = null
                // Attempt 1: Full combination (Preview + Capture + Analysis)
                try {
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCap,
                        imageAnalysis
                    )
                } catch (e1: Exception) {
                    e1.printStackTrace()
                    // Attempt 2: Fallback without Analysis (Preview + Capture)
                    try {
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCap
                        )
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                        // Attempt 3: If back camera failed, try front or any available
                        if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                            try {
                                camera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_FRONT_CAMERA,
                                    preview,
                                    imageCap
                                )
                                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                            } catch (e3: Exception) {
                                e3.printStackTrace()
                            }
                        }
                    }
                }

                if (camera != null) {
                    cameraControl = camera.cameraControl
                    cameraInfo = camera.cameraInfo

                    camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                        zoomRatio = state.zoomRatio
                        minZoomRatio = state.minZoomRatio
                        maxZoomRatio = state.maxZoomRatio
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun capturePhoto() {
        val cap = imageCapture
        if (cap == null) {
            Toast.makeText(
                context,
                if (isArabic) "جاري تشغيل الكاميرا، يرجى الانتظار ثانية..." else "Initializing camera, please wait…",
                Toast.LENGTH_SHORT
            ).show()
            launchSystemCamera()
            return
        }
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
                        try {
                            // Check EXIF rotation
                            val exif = ExifInterface(photoFile.absolutePath)
                            val orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                            val rotationDegrees = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }

                            var bmp = ImageProcessor.loadBitmapFromFile(photoFile.absolutePath, 2400)
                            if (bmp != null) {
                                if (rotationDegrees != 0) {
                                    val rotated = ImageProcessor.rotateBitmap(bmp, rotationDegrees)
                                    if (rotated != bmp) {
                                        bmp.recycle()
                                        bmp = rotated
                                    }
                                }

                                val rawPath = ImageProcessor.saveBitmapToFile(context, bmp, "raw_")

                                // Perspective crop & filter safely
                                val procBmp = try {
                                    val quad = detectedQuad ?: ImageProcessor.detectDocumentQuad(bmp)
                                    val warped = ImageProcessor.warpPerspective(bmp, quad)
                                    val filtered = ImageProcessor.applyFilter(warped, FilterType.AUTO)
                                    if (warped != bmp && warped != filtered) warped.recycle()
                                    filtered
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    ImageProcessor.applyFilter(bmp, FilterType.AUTO)
                                }

                                val procPath = ImageProcessor.saveBitmapToFile(context, procBmp, "proc_")

                                if (procBmp != bmp) procBmp.recycle()
                                bmp.recycle()

                                triggerHapticFeedback()

                                when (scanMode) {
                                    ScanCameraMode.DOCUMENT -> {
                                        isCapturing = false
                                        onDocumentCaptured(listOf(Pair(rawPath, procPath)))
                                    }
                                    ScanCameraMode.PASSPORT -> {
                                        if (!isPassportFrontDone) {
                                            passportFrontPath = procPath
                                            isPassportFrontDone = true
                                            isCapturing = false
                                            captureCooldown = true
                                            stabilityCount = 0
                                            isDocumentStable = false
                                            autoCaptureProgress = 0f
                                            delay(1000)
                                            captureCooldown = false
                                        } else {
                                            isCapturing = false
                                            val front = passportFrontPath ?: procPath
                                            onPassportCaptured?.invoke(front, procPath) ?: onIdCardCaptured(front, procPath)
                                        }
                                    }
                                    ScanCameraMode.BATCH -> {
                                        batchPages.add(Pair(rawPath, procPath))
                                        isCapturing = false
                                        captureCooldown = true
                                        stabilityCount = 0
                                        isDocumentStable = false
                                        autoCaptureProgress = 0f
                                        delay(1400)
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
                                            delay(1000)
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
                                Toast.makeText(context, if (isArabic) "تعذر معالجة الصورة، جرب مجدداً" else "Could not decode captured photo", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            isCapturing = false
                            Toast.makeText(context, if (isArabic) "حدث خطأ أثناء حفظ الصورة" else "Error processing photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    showFlashEffect = false
                    isCapturing = false
                    Toast.makeText(
                        context,
                        if (isArabic) "تعذر التصوير عبر الكاميرا الداخلية، جاري فتح كاميرا النظام..." else "Camera capture error, opening system camera…",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchSystemCamera()
                }
            }
        )
    }

    // Auto Capture countdown
    LaunchedEffect(isDocumentStable, isAutoCaptureEnabled, isCapturing, captureCooldown) {
        if (isAutoCaptureEnabled && isDocumentStable && !isCapturing && !captureCooldown) {
            autoCaptureProgress = 0f
            val steps = 10
            for (step in 1..steps) {
                delay(70)
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
        if (!hasCameraPermission) {
            PermissionRationaleScreen(
                isArabic = isArabic,
                onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSelectFromGallery = { galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onNavigateBack = onNavigateBack
            )
        } else {
            // Camera Preview View
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { previewViewRef = it }
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

            // 3x3 Grid Lines Overlay
            if (showGridLines) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineCol = Color.White.copy(alpha = 0.25f)
                    val stroke = Stroke(width = 1.dp.toPx())
                    drawLine(lineCol, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke.width)
                    drawLine(lineCol, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke.width)
                    drawLine(lineCol, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke.width)
                    drawLine(lineCol, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke.width)
                }
            }

            // Live Document Quad Overlay & Mode Guides
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeColor = if (isDocumentStable) Emerald400 else CyanScan
                val strokeW = if (isDocumentStable) 3.5.dp.toPx() else 2.dp.toPx()

                if (scanMode == ScanCameraMode.PASSPORT) {
                    // Passport Guide Frame (Standard Passport spread)
                    val padX = size.width * 0.08f
                    val w = size.width - (padX * 2)
                    val h = (w * 1.36f).coerceAtMost(size.height * 0.65f)
                    val topY = (size.height - h) / 2.2f

                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(padX, topY),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = Stroke(width = strokeW)
                    )

                    // Photo box guide (left/top area of passport bio page)
                    val photoW = w * 0.32f
                    val photoH = photoW * 1.3f
                    drawRoundRect(
                        color = strokeColor.copy(alpha = 0.55f),
                        topLeft = Offset(padX + 16.dp.toPx(), topY + 22.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(photoW, photoH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // MRZ guide lines (bottom area of passport)
                    val mrzY1 = topY + h - 38.dp.toPx()
                    val mrzY2 = topY + h - 18.dp.toPx()
                    drawLine(strokeColor.copy(alpha = 0.5f), Offset(padX + 16.dp.toPx(), mrzY1), Offset(padX + w - 16.dp.toPx(), mrzY1), strokeWidth = 1.5.dp.toPx())
                    drawLine(strokeColor.copy(alpha = 0.5f), Offset(padX + 16.dp.toPx(), mrzY2), Offset(padX + w - 16.dp.toPx(), mrzY2), strokeWidth = 1.5.dp.toPx())
                } else if (scanMode == ScanCameraMode.ID_CARD) {
                    // ID Card Frame
                    val padX = size.width * 0.10f
                    val w = size.width - (padX * 2)
                    val h = w * 0.63f
                    val topY = (size.height - h) / 2.2f

                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(padX, topY),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = Stroke(width = strokeW)
                    )

                    // Photo box guide
                    val photoW = w * 0.28f
                    val photoH = photoW * 1.25f
                    drawRoundRect(
                        color = strokeColor.copy(alpha = 0.55f),
                        topLeft = Offset(padX + 14.dp.toPx(), topY + 16.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(photoW, photoH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                } else {
                    val q = detectedQuad
                    if (q != null) {
                        val p = Path().apply {
                            moveTo(q.topLeft.x * size.width, q.topLeft.y * size.height)
                            lineTo(q.topRight.x * size.width, q.topRight.y * size.height)
                            lineTo(q.bottomRight.x * size.width, q.bottomRight.y * size.height)
                            lineTo(q.bottomLeft.x * size.width, q.bottomLeft.y * size.height)
                            close()
                        }

                        val borderCol = if (isDocumentStable) SemanticSuccess else GoldBase.copy(alpha = 0.85f)

                        if (isDocumentStable) {
                            drawPath(path = p, color = SemanticSuccess.copy(alpha = 0.12f))
                        }

                        drawPath(path = p, color = borderCol, style = Stroke(width = strokeW))

                        val cornerColor = if (isDocumentStable) SemanticSuccess else GoldBase
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
            }

            // Tap to focus indicator
            focusPoint?.let { pt ->
                Box(
                    modifier = Modifier
                        .offset(x = (pt.x - 32).dp, y = (pt.y - 32).dp)
                        .size(64.dp)
                        .border(1.8.dp, GoldBase, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(6.dp).background(GoldBase, CircleShape))
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
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                // Auto Capture Toggle Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isAutoCaptureEnabled) GoldBase else Color.Black.copy(alpha = 0.55f),
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
                            text = if (isAutoCaptureEnabled) (if (isArabic) "التقاط تلقائي" else "Auto Capture") else (if (isArabic) "يدوي" else "Manual"),
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
                        ScanCameraMode.DOCUMENT -> if (isDocumentStable) {
                            if (isArabic) "ثبّت الهاتف — جاري التقاط المستند تلقائياً..." else "Hold steady — Auto-capturing document…"
                        } else {
                            if (isArabic) "خاصية تصوير المستندات تلقائياً مفعلة — وجّه الكاميرا نحو المستند" else "Auto-capture active — Align document inside frame"
                        }
                        ScanCameraMode.ID_CARD -> if (!isIdCardFrontDone) {
                            if (isArabic) "الخطوة 1: مسح الوجه الأمامي للبطاقة" else "Step 1: Scan ID Front"
                        } else {
                            if (isArabic) "الخطوة 2: مسح الوجه الخلفي (أو اضغط 'تم' لاكتمال وجه واحد)" else "Step 2: Scan ID Back (or tap 'Done')"
                        }
                        ScanCameraMode.BATCH -> {
                            if (isArabic) "تصوير متعدد: ${batchPages.size} صفحات ملتقطة" else "Batch Mode: ${batchPages.size} pages scanned"
                        }
                        ScanCameraMode.PASSPORT -> if (!isPassportFrontDone) {
                            if (isDocumentStable) {
                                if (isArabic) "ثبّت الهاتف — جاري التقاط صفحة بيانات الجواز..." else "Hold steady — Capturing passport bio page…"
                            } else {
                                if (isArabic) "الخطوة 1: مسح صفحة بيانات وصورة الجواز" else "Step 1: Scan passport bio-data page"
                            }
                        } else {
                            if (isDocumentStable) {
                                if (isArabic) "ثبّت الهاتف — جاري التقاط الصفحة الثانية..." else "Hold steady — Capturing 2nd page…"
                            } else {
                                if (isArabic) "الخطوة 2: مسح صفحة إضافية (أو اضغط 'تم' لاكتمال الجواز)" else "Step 2: Scan additional page (or tap 'Done')"
                            }
                        }
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
                    .padding(bottom = 12.dp),
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

                // Shutter Button Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left action: Import from Gallery
                    IconButton(
                        onClick = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = if (isArabic) "المعرض" else "Gallery",
                            tint = Color.White
                        )
                    }

                    // Center Shutter Button with animated auto-capture countdown ring
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clickable { capturePhoto() }
                            .testTag("camera_shutter_btn"),
                        contentAlignment = Alignment.Center
                    ) {
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

                    // Right action: Done (in batch mode) or System Camera fallback
                    if (scanMode == ScanCameraMode.BATCH && batchPages.isNotEmpty()) {
                        Button(
                            onClick = { onDocumentCaptured(batchPages.toList()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (isArabic) "تم (${batchPages.size})" else "Done (${batchPages.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else if (scanMode == ScanCameraMode.PASSPORT && isPassportFrontDone) {
                        Button(
                            onClick = {
                                val front = passportFrontPath ?: ""
                                onPassportCaptured?.invoke(front, "") ?: onIdCardCaptured(front, "")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (isArabic) "تم (صفحة 1)" else "Done (1 Page)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else if (scanMode == ScanCameraMode.ID_CARD && isIdCardFrontDone) {
                        Button(
                            onClick = {
                                val front = idCardFrontPath ?: ""
                                onIdCardCaptured(front, "")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (isArabic) "تم (وجه 1)" else "Done (Front)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        // System camera button as immediate hardware fallback
                        IconButton(
                            onClick = launchSystemCamera,
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = if (isArabic) "كاميرا النظام" else "System Camera",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Mode Selector Bar (شريط سفلي لتغيير نوع التصوير: تصوير / تصوير متعدد / تصوير بطاقة / تصوير جواز)
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ScanCameraMode.values().forEach { m ->
                            val isSelected = scanMode == m
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = if (isSelected) Emerald400 else Color.Transparent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .clickable {
                                        triggerHapticFeedback()
                                        scanMode = m
                                        if (m == ScanCameraMode.DOCUMENT) {
                                            isAutoCaptureEnabled = true
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = when (m) {
                                            ScanCameraMode.DOCUMENT -> Icons.Default.DocumentScanner
                                            ScanCameraMode.BATCH -> Icons.Default.BurstMode
                                            ScanCameraMode.ID_CARD -> Icons.Default.Badge
                                            ScanCameraMode.PASSPORT -> Icons.Default.MenuBook
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                        tint = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f)
                                    )
                                    Text(
                                        text = if (isArabic) m.titleAr else m.titleEn,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
