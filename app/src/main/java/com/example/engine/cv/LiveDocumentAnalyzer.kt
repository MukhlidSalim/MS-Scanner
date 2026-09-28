package com.example.engine.cv

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class LiveDetectionPhase {
    /** No document in view. */
    SEARCHING,
    /** Document found but still moving / not yet held long enough / waiting for a new page. */
    TRACKING,
    /** Document held steady long enough with sufficient confidence: auto-capture may fire. */
    STABLE
}

/**
 * Immutable snapshot published to the UI (always on the main thread).
 *
 * [quad] / [captureQuad] are normalized to the ORIENTED analysis crop frame of size
 * [frameWidth] x [frameHeight]. With Preview + ImageCapture + ImageAnalysis bound in one
 * UseCaseGroup with a ViewPort, that frame is exactly the visible preview region and the
 * field of view of the saved photo, so the same normalized quad is valid for overlay AND capture.
 */
data class LiveDetectionState(
    val quad: DocumentQuad?,
    val captureQuad: DocumentQuad?,
    val frameWidth: Int,
    val frameHeight: Int,
    val confidence: Float,
    val phase: LiveDetectionPhase,
    val stableProgress: Float
) {
    companion object {
        val EMPTY = LiveDetectionState(null, null, 1, 1, 0f, LiveDetectionPhase.SEARCHING, 0f)
    }
}

/**
 * Time-based stability tracker with hysteresis.
 *
 * Replaces the previous frame-count logic, which reset to zero on any single noisy frame, required
 * 10 consecutive "perfect" UI ticks plus a separate 1.8 s countdown, compared raw luma samples
 * (sensitive to auto-exposure/flicker) and read Compose state from the analyzer thread.
 *
 * Confined to the analyzer thread (not thread-safe by design).
 */
class DetectionStabilizer(
    private val holdNs: Long = 900_000_000L,
    private val graceNs: Long = 400_000_000L,
    private val moveTolerance: Float = 0.03f
) {
    private var anchor: DocumentQuad? = null
    private var display: DocumentQuad? = null
    private var latest: DocumentQuad? = null
    private var stableSinceNs = 0L
    private var lastSeenNs = 0L
    private var confidenceEma = 0f
    private var frameW = 1
    private var frameH = 1

    fun reset() {
        anchor = null; display = null; latest = null
        stableSinceNs = 0L; lastSeenNs = 0L; confidenceEma = 0f
    }

    fun update(detection: DocumentDetection?, nowNs: Long): LiveDetectionState {
        if (detection != null && detection.confidence >= DocumentDetector.MIN_CONFIDENCE) {
            frameW = detection.imageWidth
            frameH = detection.imageHeight
            val q = detection.quad
            val a = anchor
            // Tolerance grows slightly with document size (a big page naturally jitters more in px).
            val tol = moveTolerance * (0.6f + 0.8f * sqrt(q.area().coerceIn(0f, 1f)))
            if (a == null || q.maxCornerDistance(a) > tol) {
                anchor = q
                stableSinceNs = nowNs
                confidenceEma = detection.confidence
            } else {
                anchor = a.lerpTo(q, 0.25f) // follow slow drift without resetting the timer
                confidenceEma = confidenceEma * 0.7f + detection.confidence * 0.3f
            }
            val d = display
            display = if (d == null) q else {
                val jump = q.maxCornerDistance(d)
                d.lerpTo(q, if (jump > 0.08f) 0.85f else 0.45f)
            }
            latest = q
            lastSeenNs = nowNs
        } else if (anchor != null && nowNs - lastSeenNs > graceNs) {
            reset()
        }

        val a = anchor ?: return LiveDetectionState.EMPTY
        val fresh = nowNs - lastSeenNs <= 180_000_000L
        val held = nowNs - stableSinceNs
        val progress = (held.toFloat() / holdNs).coerceIn(0f, 1f)
        val stable = fresh && held >= holdNs && confidenceEma >= DocumentDetector.AUTO_CAPTURE_CONFIDENCE
        return LiveDetectionState(
            quad = display ?: a,
            captureQuad = latest ?: a,
            frameWidth = frameW,
            frameHeight = frameH,
            confidence = confidenceEma,
            phase = if (stable) LiveDetectionPhase.STABLE else LiveDetectionPhase.TRACKING,
            stableProgress = if (confidenceEma >= DocumentDetector.AUTO_CAPTURE_CONFIDENCE) progress else 0f
        )
    }
}

/**
 * CameraX analyzer: Y-plane detection + stability, publishing [LiveDetectionState] on [callbackExecutor].
 * Use with STRATEGY_KEEP_ONLY_LATEST and a single-thread executor.
 */
class LiveDocumentAnalyzer(
    private val callbackExecutor: Executor,
    private val onState: (LiveDetectionState) -> Unit
) : ImageAnalysis.Analyzer {

    /** Expected long/short side ratio (ID card 1.586, passport 1.42) or null for generic documents. */
    @Volatile var expectedAspectRatio: Float? = null

    /** When false, frames are dropped immediately (e.g. while a capture is being processed). */
    @Volatile var enabled: Boolean = true

    @Volatile private var resetRequested = false
    @Volatile private var newSceneRequested = false

    private val stabilizer = DetectionStabilizer()
    private var lastAnalyzeNs = 0L
    private var awaitingNewScene = false
    private var sceneReference: FloatArray? = null
    private var sceneLostSinceNs = 0L

    /** Clears tracking (mode change, camera switch, after navigation). */
    fun reset() { resetRequested = true }

    /**
     * After a capture in multi-capture modes (batch / 2-sided cards): do not re-arm auto-capture on
     * the very same page. Re-arms once the scene content changes (page turned) or the document leaves.
     */
    fun requireNewScene() { newSceneRequested = true }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (!enabled || now - lastAnalyzeNs < MIN_INTERVAL_NS) return
            lastAnalyzeNs = now

            if (resetRequested) {
                resetRequested = false
                stabilizer.reset()
                awaitingNewScene = false
                sceneReference = null
            }
            val signature = sceneSignature(image)
            if (newSceneRequested) {
                newSceneRequested = false
                stabilizer.reset()
                awaitingNewScene = true
                sceneReference = signature
                sceneLostSinceNs = 0L
            }

            val detection = DocumentDetector.detectFromImageProxy(image, expectedAspectRatio)
            var state = stabilizer.update(detection, now)

            if (awaitingNewScene) {
                val ref = sceneReference
                val changed = ref != null && signature != null && signatureDistance(ref, signature) > NEW_SCENE_THRESHOLD
                if (detection == null) {
                    if (sceneLostSinceNs == 0L) sceneLostSinceNs = now
                } else {
                    sceneLostSinceNs = 0L
                }
                val lostLongEnough = sceneLostSinceNs != 0L && now - sceneLostSinceNs > 500_000_000L
                if (changed || lostLongEnough) {
                    awaitingNewScene = false
                    sceneReference = null
                    stabilizer.reset()
                    state = stabilizer.update(detection, now)
                } else {
                    state = state.copy(
                        phase = if (state.quad == null) LiveDetectionPhase.SEARCHING else LiveDetectionPhase.TRACKING,
                        stableProgress = 0f
                    )
                }
            }
            val published = state
            callbackExecutor.execute { onState(published) }
        } catch (_: Throwable) {
            // Analysis must never kill the analyzer thread.
        } finally {
            image.close()
        }
    }

    /** 8x8 grid of 3x3-averaged luma samples inside the crop rect (orientation-independent comparison). */
    private fun sceneSignature(image: ImageProxy): FloatArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val crop = image.cropRect
        if (crop.width() < 16 || crop.height() < 16) return null
        val buffer = plane.buffer
        val limit = buffer.limit()
        val rs = plane.rowStride
        val ps = plane.pixelStride.coerceAtLeast(1)
        val grid = 8
        val out = FloatArray(grid * grid)
        for (gy in 0 until grid) {
            val cy = crop.top + (crop.height() * (gy + 0.5f) / grid).toInt()
            for (gx in 0 until grid) {
                val cx = crop.left + (crop.width() * (gx + 0.5f) / grid).toInt()
                var sum = 0; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val idx = (cy + dy * 2) * rs + (cx + dx * 2) * ps
                    if (idx in 0 until limit) { sum += buffer.get(idx).toInt() and 0xFF; n++ }
                }
                out[gy * grid + gx] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
        // Normalize brightness so auto-exposure changes are not mistaken for a new page.
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        return out
    }

    private fun signatureDistance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until min(a.size, b.size)) s += abs(a[i] - b[i])
        return s / max(1, min(a.size, b.size))
    }

    private companion object {
        const val MIN_INTERVAL_NS = 66_000_000L // ~15 analyses per second at most
        const val NEW_SCENE_THRESHOLD = 9f
    }
}
