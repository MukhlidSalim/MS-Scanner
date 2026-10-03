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
 * Temporal filter + stability tracker (analyzer thread only, not thread-safe by design).
 *
 * Root cause of the jumping overlay: every accepted measurement moved the displayed quad 45-85 % of the way
 * to it, INCLUDING single-frame outliers (the detector switching to another rectangle for one frame), and
 * large jumps were followed even faster (0.85). Now:
 *  - Outlier gate: a measurement farther than [jumpThreshold] from the displayed quad is accepted only when
 *    the next measurement confirms it (two consistent frames), then the overlay snaps to it once.
 *  - Adaptive smoothing: alpha grows with the distance (0.20 for sensor jitter -> 0.75 for real motion),
 *    so a still document is steady and a moving one is followed without lag.
 *  - The smoothed quad is also returned as the PRIOR for the next detection (temporal continuity).
 * Python mirror on the same frames: still-document overlay motion 0.02 % of the frame per frame
 * (previous filter 0.17 %).
 */
class DetectionStabilizer(
    private val holdNs: Long = 900_000_000L,
    private val graceNs: Long = 500_000_000L,
    private val moveTolerance: Float = 0.03f,
    private val jumpThreshold: Float = 0.10f
) {
    private var anchor: DocumentQuad? = null
    private var display: DocumentQuad? = null
    private var latest: DocumentQuad? = null
    private var pending: DocumentQuad? = null
    private var pendingCount = 0
    private var stableSinceNs = 0L
    private var lastSeenNs = 0L
    private var confidenceEma = 0f
    private var frameW = 1
    private var frameH = 1

    fun reset() {
        anchor = null; display = null; latest = null; pending = null; pendingCount = 0
        stableSinceNs = 0L; lastSeenNs = 0L; confidenceEma = 0f
    }

    /** Smoothed quad of the tracked document (oriented frame), used as the detector prior. */
    fun currentQuad(): DocumentQuad? = display

    fun update(detection: DocumentDetection?, nowNs: Long): LiveDetectionState {
        if (detection != null && detection.confidence >= DocumentDetector.MIN_CONFIDENCE) {
            frameW = detection.imageWidth
            frameH = detection.imageHeight
            val q = detection.quad
            lastSeenNs = nowNs
            var accepted: DocumentQuad? = null
            val d = display
            if (d == null) {
                display = q
                accepted = q
            } else {
                val dist = q.maxCornerDistance(d)
                if (dist > jumpThreshold) {
                    val p = pending
                    if (p != null && q.maxCornerDistance(p) < 0.04f) pendingCount++ else { pending = q; pendingCount = 1 }
                    if (pendingCount >= 2) {
                        display = q
                        accepted = q
                        pending = null
                        pendingCount = 0
                    }
                } else {
                    pending = null
                    pendingCount = 0
                    val alpha = (0.20f + 6f * dist).coerceIn(0.20f, 0.75f)
                    display = d.lerpTo(q, alpha)
                    accepted = q
                }
            }
            val shown = display
            if (accepted != null && shown != null) {
                latest = accepted
                val a = anchor
                // Tolerance grows slightly with document size (a big page naturally jitters more in px).
                val tol = moveTolerance * (0.6f + 0.8f * sqrt(shown.area().coerceIn(0f, 1f)))
                if (a == null || shown.maxCornerDistance(a) > tol) {
                    anchor = shown
                    stableSinceNs = nowNs
                    confidenceEma = detection.confidence
                } else {
                    anchor = a.lerpTo(shown, 0.2f) // follow slow drift without resetting the timer
                    confidenceEma = confidenceEma * 0.7f + detection.confidence * 0.3f
                }
            }
        } else if (anchor != null && nowNs - lastSeenNs > graceNs) {
            reset()
        }
        val a = anchor ?: return LiveDetectionState.EMPTY
        val fresh = nowNs - lastSeenNs <= 250_000_000L
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
            // The previous tracked quad is the prior: the same sheet is preferred over a competing
            // rectangle of similar score (no alternation between page and printed table).
            val detection = DocumentDetector.detectFromImageProxy(image, expectedAspectRatio, stabilizer.currentQuad())
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
