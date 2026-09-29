package com.example.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.data.repository.AppPreferences
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.LiveDetectionPhase
import com.example.engine.cv.ProcessedPage
import com.example.engine.cv.QuadStore
import com.example.ui.screens.camera.components.PermissionRationaleScreen
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class ScanCameraMode(val titleEn: String, val titleAr: String) {
    DOCUMENT("Document", "مستند"),
    BATCH("Batch", "متعدد"),
    ID_CARD("ID Card", "بطاقة"),
    PASSPORT("Passport", "جواز")
}

/**
 * Camera scanning screen.
 *
 * Architecture (single authoritative path):
 *   CameraX ImageAnalysis -> LiveDocumentAnalyzer (DocumentDetector, off the main thread)
 *   -> DetectionStabilizer -> DocumentScanController.state (main thread only)
 *   -> DocumentDetectionOverlay + AutoCaptureEffect
 *   Shutter -> ImageCapture -> DocumentPipeline.processCapturedFile(prior = live quad)
 *   (EXIF -> upright raw -> full-resolution re-detection guided by the live quad -> warp -> AUTO filter)
 *
 * Gallery / system camera use the SAME pipeline (processUri / processFile). No image work runs on
 * the main thread. When detection fails, the full frame is kept and the user can crop manually in
 * the editor (the raw image + quad sidecar are always preserved).
 *
 * Multi-page (BATCH): capture is continuous (Page 1 -> Page 2 -> ... -> Finish); the editor is never
 * opened between pages. Auto-capture re-arms only when the page changes (no duplicate shots).
 */
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
    modifier: Modifier = Modifier,
    /** True for a normal "Scan" entry: the camera opens in the mode the user chose last time. */
    useSavedMode: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"

    // Persisted camera settings (Auto/Manual, flash, torch, grid, camera, last mode).
    val cameraPrefs = remember { AppPreferences(context) }
    val savedMode = remember {
        runCatching { ScanCameraMode.valueOf(cameraPrefs.cameraLastMode) }.getOrDefault(ScanCameraMode.DOCUMENT)
    }
    // Replacing a single page never makes sense in batch mode.
    val requestedMode = if (useSavedMode && replacePageId == 0L) savedMode else initialMode
    val startMode = if (replacePageId > 0L && requestedMode == ScanCameraMode.BATCH) ScanCameraMode.DOCUMENT else requestedMode
    var scanMode by remember { mutableStateOf(startMode) }
    val isMultiPage = scanMode == ScanCameraMode.BATCH

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

    // Rebind camera on resume (prevents frozen preview after returning from system camera / picker).
    var resumedCount by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumedCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dedicated analysis executor; camera is unbound before the executor is shut down.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        onDispose {
            try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
            analysisExecutor.shutdown()
        }
    }

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }

    var cameraSelector by remember {
        mutableStateOf(if (cameraPrefs.cameraFrontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
    }
    var flashMode by remember { mutableStateOf(cameraPrefs.cameraFlashMode) }
    var isTorchActive by remember { mutableStateOf(cameraPrefs.cameraTorch) }

    var zoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(4f) }

    var showGridLines by remember { mutableStateOf(cameraPrefs.cameraGrid) }
    var isPhoneFlat by remember { mutableStateOf(false) }

    var isAutoCaptureEnabled by remember { mutableStateOf(cameraPrefs.cameraAutoCapture) }
    // Save every change immediately, so the next camera session starts with the same settings.
    LaunchedEffect(isAutoCaptureEnabled) { cameraPrefs.cameraAutoCapture = isAutoCaptureEnabled }
    LaunchedEffect(flashMode, isTorchActive) {
        cameraPrefs.cameraFlashMode = flashMode
        cameraPrefs.cameraTorch = isTorchActive
    }
    LaunchedEffect(showGridLines) { cameraPrefs.cameraGrid = showGridLines }
    LaunchedEffect(cameraSelector) { cameraPrefs.cameraFrontFacing = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA }

    // ---- Single owner of live detection / stability / auto-capture state ----
    val scanController = rememberDocumentScanController(context)
    val liveState = scanController.state
    val isDocumentStable = liveState.phase == LiveDetectionPhase.STABLE
    val isDocumentTracked = liveState.quad != null
    val autoCaptureProgress = liveState.stableProgress

    var isAutoCancelled by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureCooldown by remember { mutableStateOf(false) }

    LaunchedEffect(scanMode) {
        scanController.setMode(scanMode)
        isAutoCancelled = false
    }
    // Re-allow auto-capture once the document leaves the frame after a manual cancel.
    LaunchedEffect(liveState.phase) {
        if (liveState.phase == LiveDetectionPhase.SEARCHING) isAutoCancelled = false
    }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // Multi-page batch, ID card and passport accumulation (raw + processed are always kept together).
    val batchPages = remember { mutableStateListOf<Pair<String, String>>() }
    var frontPage by remember { mutableStateOf<Pair<String, String>?>(null) }
    val isIdCardFrontDone = scanMode == ScanCameraMode.ID_CARD && frontPage != null
    val isPassportFrontDone = scanMode == ScanCameraMode.PASSPORT && frontPage != null

    // Switching mode discards a half-finished 2-sided capture (it belonged to the previous mode).
    LaunchedEffect(scanMode) { frontPage = null }

    var showFlashEffect by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun deletePageFiles(page: Pair<String, String>) {
        runCatching {
            if (page.first.isNotBlank()) {
                QuadStore.delete(page.first)
                File(page.first).delete()
            }
            if (page.second.isNotBlank() && page.second != page.first) File(page.second).delete()
        }
    }

    fun discardAndExit() {
        batchPages.forEach { deletePageFiles(it) }
        batchPages.clear()
        frontPage?.let { deletePageFiles(it) }
        frontPage = null
        onNavigateBack()
    }

    val hasUnsavedCaptures = batchPages.isNotEmpty() || frontPage != null
    BackHandler(enabled = hasUnsavedCaptures) { showDiscardDialog = true }

    // Accelerometer listener for level indicator
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.size >= 3) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    isPhoneFlat = abs(x) < 1.6f && abs(y) < 1.6f && abs(z) > 8.0f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accelerometer != null) {
            sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    /**
     * Routes processed pages to the current mode. Shared by camera, gallery and system camera so
     * every source behaves identically.
     */
    fun deliverPages(pages: List<Pair<String, String>>) {
        if (pages.isEmpty()) return
        when (scanMode) {
            ScanCameraMode.DOCUMENT -> onDocumentCaptured(listOf(pages.first()))
            ScanCameraMode.BATCH -> batchPages.addAll(pages)
            ScanCameraMode.ID_CARD, ScanCameraMode.PASSPORT -> {
                val front = frontPage
                when {
                    pages.size >= 2 -> {
                        frontPage = null
                        onDocumentCaptured(listOf(pages[0], pages[1]))
                    }
                    front == null -> frontPage = pages[0]
                    else -> {
                        frontPage = null
                        onDocumentCaptured(listOf(front, pages[0]))
                    }
                }
            }
        }
    }

    // Gallery import: same pipeline as the camera (EXIF, bounded decode, detection, warp, filter), off main.
    val multipleGalleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isCapturing = true
            scanController.onCaptureStarted()
            try {
                val aspect = DocumentScanController.expectedAspectFor(scanMode)
                val imported = mutableListOf<Pair<String, String>>()
                var failed = 0
                for ((index, uri) in uris.withIndex()) {
                    val page: ProcessedPage? = try {
                        DocumentPipeline.processUri(context, uri, expectedAspectRatio = aspect, prefix = "import_p${index + 1}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    if (page != null) imported += Pair(page.rawPath, page.processedPath) else failed++
                }
                if (failed > 0) {
                    Toast.makeText(
                        context,
                        if (isArabic) "تعذر استيراد $failed صورة" else "Could not import $failed image(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                deliverPages(imported)
            } finally {
                isCapturing = false
                scanController.onCaptureFinished(stayOnCamera = true)
            }
        }
    }

    val launchGalleryImport = {
        multipleGalleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // System camera fallback (devices where CameraX cannot bind).
    var tempCameraFile by remember { mutableStateOf<File?>(null) }
    val systemCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempCameraFile
        tempCameraFile = null
        if (!success || file == null || !file.exists()) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            isCapturing = true
            try {
                val page = try {
                    DocumentPipeline.processFile(
                        context, file.absolutePath,
                        expectedAspectRatio = DocumentScanController.expectedAspectFor(scanMode),
                        prefix = "cam"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (page != null) {
                    deliverPages(listOf(Pair(page.rawPath, page.processedPath)))
                } else {
                    Toast.makeText(context, if (isArabic) "تعذر معالجة الصورة" else "Could not process the photo", Toast.LENGTH_SHORT).show()
                }
            } finally {
                file.delete()
                isCapturing = false
            }
        }
    }

    val launchSystemCamera: () -> Unit = {
        try {
            val dir = File(context.cacheDir, "camera_scans").apply { if (!exists()) mkdirs() }
            val file = File(dir, "sys_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            systemCameraLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            launchGalleryImport()
        }
    }

    // CameraX binding: Preview + ImageCapture + ImageAnalysis in one UseCaseGroup with a ViewPort
    // (built only after layout), so overlay, analysis and photo share the same field of view.
    LaunchedEffect(hasCameraPermission, cameraSelector, previewViewRef, resumedCount) {
        val pView = previewViewRef
        if (!hasCameraPermission || pView == null) return@LaunchedEffect
        try {
            awaitLaidOut(pView)
            val provider = awaitCameraProvider(context)
            cameraProvider = provider
            val bound = bindScannerCamera(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                previewView = pView,
                cameraSelector = cameraSelector,
                flashMode = flashMode,
                analysisExecutor = analysisExecutor,
                controller = scanController
            )
            scanController.reset()
            cameraInfo?.zoomState?.removeObservers(lifecycleOwner)
            if (bound == null) {
                imageCapture = null
                cameraControl = null
                cameraInfo = null
                return@LaunchedEffect
            }
            imageCapture = bound.imageCapture
            cameraControl = bound.camera.cameraControl
            cameraInfo = bound.camera.cameraInfo
            if (isTorchActive) bound.camera.cameraControl.enableTorch(true)
            // Single observer per binding (previous code added one more on every resume).
            bound.camera.cameraInfo.zoomState.removeObservers(lifecycleOwner)
            bound.camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                zoomRatio = state.zoomRatio
                minZoomRatio = state.minZoomRatio
                maxZoomRatio = state.maxZoomRatio
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            scanController.reset()
        }
    }

    fun capturePhoto() {
        val cap = imageCapture
        if (cap == null) {
            launchSystemCamera()
            return
        }
        if (isCapturing || captureCooldown) return
        isCapturing = true
        showFlashEffect = true

        // Snapshot at shutter time, read from the controller (never a stale composition value).
        val priorQuad = scanController.captureQuadForShutter()
        val modeAtShutter = scanMode
        scanController.onCaptureStarted()

        val cacheDir = File(context.cacheDir, "camera_scans").apply { if (!exists()) mkdirs() }
        val photoFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        cap.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    showFlashEffect = false
                    coroutineScope.launch {
                        // Decode + EXIF + detection (guided by live quad) + warp + filter: all off main.
                        val page = try {
                            scanController.processCapture(context, photoFile, modeAtShutter, priorQuad)
                        } catch (e: CancellationException) {
                            photoFile.delete()
                            throw e
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            null
                        }
                        if (page == null) {
                            photoFile.delete()
                            isCapturing = false
                            scanController.onCaptureFinished(stayOnCamera = true)
                            Toast.makeText(
                                context,
                                if (isArabic) "تعذر معالجة الصورة، حاول مرة أخرى" else "Could not process the photo, please retry",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val pair = Pair(page.rawPath, page.processedPath)
                        val staysOnCamera = when (modeAtShutter) {
                            ScanCameraMode.DOCUMENT -> false
                            ScanCameraMode.BATCH -> true
                            ScanCameraMode.ID_CARD, ScanCameraMode.PASSPORT -> frontPage == null
                        }
                        isCapturing = false
                        scanController.onCaptureFinished(stayOnCamera = staysOnCamera)
                        if (modeAtShutter == scanMode) {
                            deliverPages(listOf(pair))
                        } else {
                            deletePageFiles(pair) // mode changed while processing: never mix pages
                        }
                        if (staysOnCamera) {
                            captureCooldown = true
                            delay(700)
                            captureCooldown = false
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    photoFile.delete()
                    showFlashEffect = false
                    isCapturing = false
                    scanController.onCaptureFinished(stayOnCamera = true)
                    Toast.makeText(
                        context,
                        if (isArabic) "تعذر التصوير، حاول مرة أخرى" else "Capture failed, please retry",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // One time source: the stabilizer hold time IS the countdown (ring on the overlay).
    AutoCaptureEffect(
        controller = scanController,
        enabled = isAutoCaptureEnabled && !isCapturing && !captureCooldown &&
            !isAutoCancelled && scanController.analysisActive && !showDiscardDialog
    ) {
        capturePhoto()
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(if (isArabic) "تجاهل الصفحات الملتقطة؟" else "Discard captured pages?") },
            text = {
                Text(
                    if (isArabic) "سيتم حذف الصفحات التي لم يتم حفظها."
                    else "Pages that were not saved will be deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; discardAndExit() }) {
                    Text(if (isArabic) "تجاهل" else "Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(if (isArabic) "متابعة" else "Continue") }
            }
        )
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
                onSelectFromGallery = { launchGalleryImport() },
                onNavigateBack = onNavigateBack
            )
        } else {
            val density = LocalDensity.current
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

            if (showGridLines) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineCol = Color.White.copy(alpha = 0.25f)
                    val w = 1.dp.toPx()
                    drawLine(lineCol, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), w)
                    drawLine(lineCol, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), w)
                    drawLine(lineCol, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), w)
                    drawLine(lineCol, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), w)
                }
            }

            // ID card / passport alignment guides (visual help only, never used as the crop).
            if (scanMode == ScanCameraMode.ID_CARD || scanMode == ScanCameraMode.PASSPORT) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val guideColor = (if (isDocumentStable) Emerald400 else Color.White).copy(alpha = if (isDocumentTracked) 0.35f else 0.7f)
                    val strokeW = 2.dp.toPx()
                    val padX = size.width * if (scanMode == ScanCameraMode.PASSPORT) 0.08f else 0.10f
                    val w = size.width - padX * 2
                    val h = if (scanMode == ScanCameraMode.PASSPORT) (w / 1.42f) else (w / 1.586f)
                    val topY = (size.height - h) / 2.2f
                    drawRoundRect(
                        color = guideColor,
                        topLeft = Offset(padX, topY),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = Stroke(width = strokeW)
                    )
                }
            }

            // Live detected document (edges + corners + hold-progress ring), same bounds as PreviewView.
            DocumentDetectionOverlay(
                state = liveState,
                isFrontCamera = scanController.isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )

            focusPoint?.let { pt ->
                val (dx, dy) = with(density) { (pt.x.toDp() - 32.dp) to (pt.y.toDp() - 32.dp) }
                Box(
                    modifier = Modifier
                        .offset(x = dx, y = dy)
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

            AnimatedVisibility(
                visible = showFlashEffect,
                enter = fadeIn(animationSpec = tween(60)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White))
            }

            // Processing indicator (capture/import runs in background; UI stays responsive).
            if (isCapturing && !showFlashEffect) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Emerald400, strokeWidth = 2.dp)
                        Text(if (isArabic) "جاري المعالجة..." else "Processing…", color = Color.White, fontSize = 14.sp)
                    }
                }
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
                    onClick = { if (hasUnsavedCaptures) showDiscardDialog = true else onNavigateBack() },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isAutoCaptureEnabled) GoldBase else Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.clickable {
                        isAutoCaptureEnabled = !isAutoCaptureEnabled
                        isAutoCancelled = false
                    }
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
                    IconButton(
                        onClick = {
                            // Cycle: OFF -> AUTO -> ON -> TORCH -> OFF (TORCH is stored as OFF + torch).
                            when {
                                isTorchActive -> {
                                    flashMode = ImageCapture.FLASH_MODE_OFF
                                    isTorchActive = false
                                    cameraControl?.enableTorch(false)
                                }
                                else -> when (flashMode) {
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
                                    if (!isTorchActive) {
                                        // ON -> TORCH (continuous light, keeps flash mode OFF for capture)
                                        isTorchActive = true
                                        cameraControl?.enableTorch(true)
                                    }
                                    flashMode = ImageCapture.FLASH_MODE_OFF
                                }
                                else -> {
                                    flashMode = ImageCapture.FLASH_MODE_OFF
                                    isTorchActive = false
                                    cameraControl?.enableTorch(false)
                                }
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

                    IconButton(
                        onClick = { showGridLines = !showGridLines },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(Icons.Default.GridOn, contentDescription = "Grid", tint = if (showGridLines) Emerald400 else Color.White)
                    }

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

            // Guidance banner
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
                    val guidance = when {
                        !scanController.analysisActive ->
                            if (isArabic) "الاكتشاف التلقائي غير متاح — التقط يدوياً" else "Auto-detect unavailable — capture manually"
                        isDocumentStable ->
                            if (isArabic) "ثبّت الهاتف — جاري الالتقاط..." else "Hold steady — capturing…"
                        isDocumentTracked ->
                            if (isArabic) "تم اكتشاف المستند — ثبّت الهاتف" else "Document detected — hold steady"
                        else ->
                            if (isArabic) "وجّه الكاميرا نحو المستند" else "Point the camera at the document"
                    }
                    val step = when (scanMode) {
                        ScanCameraMode.DOCUMENT -> null
                        ScanCameraMode.BATCH -> if (isArabic) "صفحة ${batchPages.size + 1}" else "Page ${batchPages.size + 1}"
                        ScanCameraMode.ID_CARD -> if (!isIdCardFrontDone) (if (isArabic) "الوجه الأمامي" else "Front side") else (if (isArabic) "الوجه الخلفي" else "Back side")
                        ScanCameraMode.PASSPORT -> if (!isPassportFrontDone) (if (isArabic) "صفحة البيانات" else "Bio-data page") else (if (isArabic) "الصفحة الثانية" else "Second page")
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isDocumentTracked && autoCaptureProgress > 0f && isAutoCaptureEnabled) {
                            CircularProgressIndicator(
                                progress = { autoCaptureProgress },
                                modifier = Modifier.size(14.dp),
                                color = Emerald400,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            text = if (step != null) "$step • $guidance" else guidance,
                            color = if (isDocumentStable) Emerald400 else Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isAutoCaptureEnabled && isDocumentTracked && !isAutoCancelled) {
                            Text(
                                text = if (isArabic) "إلغاء" else "Cancel",
                                color = GoldBase,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { isAutoCancelled = true }
                                    .padding(start = 6.dp)
                                    .testTag("cancel_autocapture_button")
                            )
                        }
                    }
                }
            }

            // Bottom Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Batch thumbnails: last pages, tap the last one to remove it (retake).
                if (isMultiPage && batchPages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        batchPages.forEachIndexed { index, page ->
                            val isLast = index == batchPages.lastIndex
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 58.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, if (isLast) Emerald400 else Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            ) {
                                AsyncImage(
                                    model = File(page.second),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isLast) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                            .clickable {
                                                val removed = batchPages.removeAt(batchPages.lastIndex)
                                                deletePageFiles(removed)
                                                scanController.reset()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = if (isArabic) "إعادة التقاط" else "Retake", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Zoom presets
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (!isCapturing) launchGalleryImport() },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = if (isArabic) "المعرض" else "Gallery", tint = Color.White)
                    }

                    // Shutter
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clickable(enabled = !isCapturing && !captureCooldown) { capturePhoto() }
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
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(if (isCapturing) Color.Gray else if (isDocumentStable) Emerald400 else Color.White)
                        )
                    }

                    when {
                        isMultiPage && batchPages.isNotEmpty() -> {
                            Button(
                                onClick = {
                                    if (!isCapturing) {
                                        val pages = batchPages.toList()
                                        batchPages.clear() // ownership moves to the edit session: no double save / delete
                                        onDocumentCaptured(pages)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isArabic) "إنهاء (${batchPages.size})" else "Finish (${batchPages.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        frontPage != null -> {
                            // Single-sided card / passport: finish with the front only.
                            Button(
                                onClick = {
                                    val front = frontPage ?: return@Button
                                    frontPage = null
                                    onDocumentCaptured(listOf(front))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isArabic) "تم (وجه واحد)" else "Done (1 side)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = { if (!isCapturing) launchSystemCamera() },
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = if (isArabic) "كاميرا النظام" else "System Camera", tint = Color.White)
                            }
                        }
                    }
                }

                // Mode selector (locked while pages of the current mode are pending or replacing a page)
                val modeLocked = hasUnsavedCaptures || replacePageId > 0L
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
                            val enabled = !modeLocked || isSelected
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = if (isSelected) Emerald400 else Color.Transparent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .clickable(enabled = enabled && !isCapturing) {
                                        scanMode = m
                                        cameraPrefs.cameraLastMode = m.name // user's explicit choice is remembered
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
                                            ScanCameraMode.BATCH -> Icons.Default.LibraryAdd
                                            ScanCameraMode.ID_CARD -> Icons.Default.Badge
                                            ScanCameraMode.PASSPORT -> Icons.Default.MenuBook
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                        tint = when {
                                            isSelected -> Color.Black
                                            enabled -> Color.White.copy(alpha = 0.85f)
                                            else -> Color.White.copy(alpha = 0.3f)
                                        }
                                    )
                                    Text(
                                        text = if (isArabic) m.titleAr else m.titleEn,
                                        color = when {
                                            isSelected -> Color.Black
                                            enabled -> Color.White
                                            else -> Color.White.copy(alpha = 0.3f)
                                        },
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
