package com.example.ui.screens.camera

import android.content.Context
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.model.FilterType
import com.example.engine.cv.DocumentPipeline
import com.example.engine.cv.LiveDetectionPhase
import com.example.engine.cv.LiveDetectionState
import com.example.engine.cv.LiveDocumentAnalyzer
import com.example.engine.cv.ProcessedPage
import com.example.engine.cv.QuadCoordinateMapper
import com.example.engine.cv.ScanAspect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns live detection state for CameraScanScreen (built-in camera = fallback scanner).
 * All Compose state here is written on the main thread only.
 */
class DocumentScanController(context: Context) {
    var state: LiveDetectionState by mutableStateOf(LiveDetectionState.EMPTY)
        private set
    /** True when Preview/Capture/Analysis are bound with a shared ViewPort (frames are aligned). */
    var viewportActive: Boolean by mutableStateOf(false)
        internal set
    /** True when ImageAnalysis is bound at all (detection available). */
    var analysisActive: Boolean by mutableStateOf(false)
        internal set
    var isFrontCamera: Boolean by mutableStateOf(false)
    val analyzer = LiveDocumentAnalyzer(ContextCompat.getMainExecutor(context)) { s -> state = s }

    fun setMode(mode: ScanCameraMode) {
        analyzer.expectedAspectRatio = expectedAspectFor(mode)
        reset()
    }

    fun reset() {
        analyzer.reset()
        state = LiveDetectionState.EMPTY
    }

    /** Quad to use as capture prior, only when frames are guaranteed to share the same field of view. */
    fun captureQuadForShutter() = if (viewportActive && state.phase != LiveDetectionPhase.SEARCHING) state.captureQuad else null

    /** Call when a capture starts. Stops analysis work while the photo is processed. */
    fun onCaptureStarted() {
        analyzer.enabled = false
    }

    /**
     * Call after a capture finished. [stayOnCamera] = batch page / first side of ID or passport:
     * auto-capture re-arms only after the page changes (no duplicate shots of the same page).
     */
    fun onCaptureFinished(stayOnCamera: Boolean) {
        analyzer.enabled = true
        if (stayOnCamera) analyzer.requireNewScene() else analyzer.reset()
        state = LiveDetectionState.EMPTY
    }

    /** Processes a saved CameraX photo through the unified pipeline, using the live quad as prior. */
    suspend fun processCapture(
        context: Context,
        photo: File,
        mode: ScanCameraMode,
        previewQuad: com.example.engine.cv.DocumentQuad?,
        filter: FilterType? = DocumentPipeline.DEFAULT_FILTER
    ): ProcessedPage? = DocumentPipeline.processCapturedFile(
        context = context,
        file = photo,
        previewQuad = previewQuad,
        expectedAspectRatio = expectedAspectFor(mode),
        filter = filter
    )

    companion object {
        fun expectedAspectFor(mode: ScanCameraMode): Float? = when (mode) {
            ScanCameraMode.ID_CARD -> ScanAspect.ID_CARD
            ScanCameraMode.PASSPORT -> ScanAspect.PASSPORT
            else -> null
        }
    }
}

@Composable
fun rememberDocumentScanController(context: Context): DocumentScanController {
    val controller = remember { DocumentScanController(context.applicationContext) }
    DisposableEffect(controller) { onDispose { controller.analyzer.enabled = false } }
    return controller
}

/** Suspends until the CameraX provider is ready (no extra futures library needed). */
suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        try {
            cont.resume(future.get())
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }, ContextCompat.getMainExecutor(context))
}

/** Waits (max ~1 s) until the view has a real size, so the ViewPort can be built correctly. */
suspend fun awaitLaidOut(view: PreviewView) {
    var waited = 0
    while ((view.width == 0 || view.height == 0) && waited < 1000) {
        delay(16)
        waited += 16
    }
}

/** Result of [bindScannerCamera]. */
class BoundScanner(val camera: Camera, val imageCapture: ImageCapture)

/**
 * Binds Preview + ImageCapture + ImageAnalysis.
 *  1. ViewPort is used only with a valid, laid-out size (aligned frames, capture prior usable).
 *  2. Otherwise / on failure, the three use cases are bound WITHOUT ViewPort.
 *  3. Only as a last resort Preview + Capture are bound (manual capture still works).
 *
 * Analysis resolution: CameraX's default analysis stream is 640x480. After the ViewPort crop to a tall
 * phone screen only ~288x640 sensor pixels remained, i.e. a page ~115 px wide in the detector: one pixel of
 * detection noise was ~1 % of the screen, visible as a shaking overlay. The analysis stream now requests
 * 1280x960 (the detector still works on a <= 400 px copy, so the CPU cost stays bounded).
 */
fun bindScannerCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    flashMode: Int,
    analysisExecutor: ExecutorService,
    controller: DocumentScanController
): BoundScanner? {
    provider.unbindAll()
    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
    val portrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    fun newPreview() = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    fun newCapture() = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetRotation(rotation)
        .setFlashMode(flashMode)
        .build()
    @Suppress("DEPRECATION")
    fun newAnalysis() = ImageAnalysis.Builder()
        .setTargetRotation(rotation)
        // Expressed in the target-rotation frame; CameraX picks the closest supported size.
        .setTargetResolution(if (portrait) Size(960, 1280) else Size(1280, 960))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()
        .also { it.setAnalyzer(analysisExecutor, controller.analyzer) }
    controller.isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    if (previewView.width > 0 && previewView.height > 0) {
        try {
            val capture = newCapture()
            val group = UseCaseGroup.Builder()
                .addUseCase(newPreview())
                .addUseCase(capture)
                .addUseCase(newAnalysis())
                .setViewPort(
                    ViewPort.Builder(Rational(previewView.width, previewView.height), rotation)
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build()
                )
                .build()
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, group)
            controller.viewportActive = true
            controller.analysisActive = true
            return BoundScanner(camera, capture)
        } catch (_: Throwable) {
            provider.unbindAll()
        }
    }
    try {
        val capture = newCapture()
        val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, newPreview(), capture, newAnalysis())
        controller.viewportActive = false
        controller.analysisActive = true
        return BoundScanner(camera, capture)
    } catch (_: Throwable) {
        provider.unbindAll()
    }
    return try {
        val capture = newCapture()
        val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, newPreview(), capture)
        controller.viewportActive = false
        controller.analysisActive = false
        BoundScanner(camera, capture)
    } catch (_: Throwable) {
        controller.viewportActive = false
        controller.analysisActive = false
        null
    }
}

/**
 * Fires [onAutoCapture] exactly once each time the tracker enters STABLE while [enabled].
 * The stabilizer's hold time IS the countdown (shown as the progress ring): one time source.
 */
@Composable
fun AutoCaptureEffect(controller: DocumentScanController, enabled: Boolean, onAutoCapture: () -> Unit) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestCallback by rememberUpdatedState(onAutoCapture)
    LaunchedEffect(controller) {
        snapshotFlow { controller.state.phase }
            .distinctUntilChanged()
            .filter { it == LiveDetectionPhase.STABLE }
            .collect { if (latestEnabled) latestCallback() }
    }
}

/**
 * Live quad overlay. Must be laid out exactly over the PreviewView (same size and position).
 * Colors: gold while tracking, green when stable; a ring on the quad center shows hold progress.
 */
@Composable
fun DocumentDetectionOverlay(
    state: LiveDetectionState,
    isFrontCamera: Boolean,
    modifier: Modifier = Modifier,
    trackingColor: Color = Color(0xFFD4AF37),
    stableColor: Color = Color(0xFF22C55E),
    showProgress: Boolean = true
) {
    Canvas(modifier = modifier) {
        val quad = state.quad ?: return@Canvas
        val p = QuadCoordinateMapper.frameToView(
            quad, state.frameWidth, state.frameHeight, size.width, size.height,
            fillCenter = true, mirror = isFrontCamera
        )
        val stable = state.phase == LiveDetectionPhase.STABLE
        val color = if (stable) stableColor else trackingColor
        val path = Path().apply {
            moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close()
        }
        drawPath(path, color.copy(alpha = if (stable) 0.18f else 0.10f))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        for (i in 0 until 4) {
            val c = Offset(p[2 * i], p[2 * i + 1])
            drawCircle(Color.White, radius = 7.dp.toPx(), center = c)
            drawCircle(color, radius = 5.dp.toPx(), center = c)
        }
        if (showProgress && state.stableProgress > 0f) {
            val cx = (p[0] + p[2] + p[4] + p[6]) / 4f
            val cy = (p[1] + p[3] + p[5] + p[7]) / 4f
            val r = 26.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 6.dp.toPx(), center = Offset(cx, cy))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * state.stableProgress,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = ComposeSize(2 * r, 2 * r),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
