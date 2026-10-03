package com.example.engine.scanner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.BuildConfig
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google ML Kit Document Scanner (same engine as the Google Drive scanner). PRIMARY capture engine; the
 * built-in camera is only a fallback for a REAL failure.
 *
 * Root cause of "Could not start the Google scanner: NullPointerException" (release APK only):
 *   AGP 9 runs R8 in full mode; play-services-mlkit-* internals resolved by reflection were stripped and the
 *   client calls became `throw null`. Fixed in app/proguard-rules.pro (keep rules). This class adds:
 *   - Module cycle: UNAVAILABLE -> request install -> WAIT for STATE_COMPLETED -> verify availability ->
 *     retry getStartScanIntent (bounded, no loop). Deterministic errors are NOT retried.
 *   - Lifecycle: callbacks are dropped when the Activity was destroyed (language change / rotation recreate
 *     it and the new screen starts a new session), deferred until ON_START when the app is in background,
 *     and delivered exactly once.
 *   - Diagnostics: every real failure writes a full report to filesDir/crash/scanner_*.txt
 *     (Settings > Diagnostics > Share).
 */
object GoogleDocumentScanner {
    private const val TAG = "GoogleDocScanner"
    /** Google requires about 1.7 GB of device RAM; below that the API returns UNSUPPORTED. */
    private const val MIN_TOTAL_RAM_BYTES = 1_700_000_000L
    private const val INSTALL_TIMEOUT_MS = 120_000L
    /** Total getStartScanIntent() attempts in one session (first try + retries after UNAVAILABLE). */
    private const val MAX_LAUNCH_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_500L

    /** Scanner could not be used; [userMessageAr]/[userMessageEn] explain why (shown before the fallback). */
    class ScannerUnavailableException(
        val userMessageAr: String,
        val userMessageEn: String,
        cause: Throwable? = null
    ) : Exception(userMessageEn, cause)

    /** Returned by [start]; [cancel] stops any pending callback (e.g. the screen was left). */
    class Session internal constructor() {
        @Volatile internal var cancelled = false
        fun cancel() {
            cancelled = true
        }
    }

    /**
     * Device capability only (Google Play services present + enough RAM). A missing / outdated scanner
     * MODULE is not a reason to skip Google: the session installs it.
     */
    fun isSupported(context: Context): Boolean {
        val status = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }
            .getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (status == ConnectionResult.SERVICE_MISSING || status == ConnectionResult.SERVICE_INVALID ||
            status == ConnectionResult.SERVICE_DISABLED
        ) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem <= 0L || info.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    private fun options(pageLimit: Int?): GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            // Gallery import stays in the app (one import path through the app pipeline).
            .setGalleryImportAllowed(false)
            // JPEG only: the app builds its own PDF (OCR layer, page size, password…).
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            // FULL = crop, rotate, reorder, filters + ML cleaning (shadows, stains, fingers).
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .apply { if (pageLimit != null && pageLimit > 0) setPageLimit(pageLimit) }
            .build()

    /**
     * Opens the Google scanner. Exactly one of [onIntent] / [onError] is called, on the main thread, and only
     * while [activity] is alive; if the Activity is destroyed first, no callback is made (the recreated screen
     * starts its own session). [onPreparing] reports module download progress (0..100, or null if unknown).
     */
    fun start(
        activity: Activity,
        pageLimit: Int?,
        onPreparing: (progressPercent: Int?) -> Unit,
        onIntent: (IntentSender) -> Unit,
        onError: (ScannerUnavailableException) -> Unit
    ): Session {
        val session = Session()
        LaunchSession(activity, pageLimit, onPreparing, onIntent, onError, session).begin()
        return session
    }

    /** Page image URIs of a successful scan (empty when cancelled or nothing was scanned). */
    fun pageUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK) return emptyList()
        return runCatching {
            GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.map { it.imageUri }.orEmpty()
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------------------------------ session

    private class LaunchSession(
        activity: Activity,
        private val pageLimit: Int?,
        private val onPreparing: (Int?) -> Unit,
        private val onIntent: (IntentSender) -> Unit,
        private val onError: (ScannerUnavailableException) -> Unit,
        private val session: Session
    ) {
        private val activityRef = WeakReference(activity)
        private val appContext: Context = activity.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val finished = AtomicBoolean(false)
        private val startedAt = SystemClock.elapsedRealtime()
        private val timeline = ArrayList<String>()
        private var scanner: GmsDocumentScanner? = null
        private var launchAttempts = 0
        private var moduleInstallRequested = false
        private var moduleInstallResult = "not requested"
        private var installClient: ModuleInstallClient? = null
        private var installListener: InstallStatusListener? = null
        private val installTimeout = Runnable {
            if (finished.get()) return@Runnable
            stopListening()
            moduleInstallResult = "timeout after " + (INSTALL_TIMEOUT_MS / 1000) + " s"
            fail("module install", IllegalStateException("Scanner module installation timed out"))
        }

        private fun log(message: String) {
            timeline += "+" + (SystemClock.elapsedRealtime() - startedAt) + " ms  " + message
            Log.d(TAG, message)
        }

        fun begin() {
            log("session started (pageLimit=" + pageLimit + ")")
            main.post { createClientAndLaunch() }
        }

        /** True when nothing must be delivered anymore (finished, cancelled, or Activity destroyed). */
        private fun abandoned(): Boolean {
            if (finished.get()) return true
            if (session.cancelled) {
                finished.set(true)
                stopListening()
                log("cancelled by caller")
                return true
            }
            val a = activityRef.get()
            if (a == null || a.isFinishing || a.isDestroyed) {
                // Language change / rotation recreates the Activity: the new screen starts a new session.
                // Delivering here would launch from a dead ActivityResultRegistry or update a disposed screen.
                finished.set(true)
                stopListening()
                log("activity destroyed or finishing -> session abandoned, no callback")
                return true
            }
            return false
        }

        private fun createClientAndLaunch() {
            if (abandoned()) return
            scanner = try {
                GmsDocumentScanning.getClient(options(pageLimit))
            } catch (e: Throwable) {
                fail("GmsDocumentScanning.getClient", e)
                return
            }
            log("client created")
            launch()
        }

        private fun launch() {
            if (abandoned()) return
            val a = activityRef.get() ?: return
            val s = scanner ?: return
            launchAttempts++
            log("getStartScanIntent attempt " + launchAttempts)
            val task = try {
                s.getStartScanIntent(a)
            } catch (e: Throwable) {
                fail("getStartScanIntent (synchronous)", e)
                return
            }
            task.addOnSuccessListener { sender ->
                log("intent received")
                deliver { onIntent(sender) }
            }.addOnFailureListener { e -> onLaunchFailure(e) }
        }

        private fun onLaunchFailure(e: Exception) {
            if (abandoned()) return
            val code = (e as? MlKitException)?.errorCode
            log("getStartScanIntent failed: " + e.javaClass.name + " errorCode=" + code + " message=" + e.message)
            val unavailable = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
            when {
                // Module missing / outdated: install it and WAIT for the result before retrying.
                unavailable && !moduleInstallRequested -> installModuleThenRetry()
                // Module just installed but not registered yet: bounded retry.
                unavailable && launchAttempts < MAX_LAUNCH_ATTEMPTS ->
                    main.postDelayed({ launch() }, RETRY_DELAY_MS * launchAttempts)
                // UNSUPPORTED, permanent errors, or retries exhausted: real failure -> fallback camera.
                else -> fail("getStartScanIntent", e)
            }
        }

        private fun installModuleThenRetry() {
            val s = scanner ?: return
            moduleInstallRequested = true
            moduleInstallResult = "requested"
            onPreparing(null)
            log("requesting scanner module installation")
            val client = ModuleInstall.getClient(appContext)
            installClient = client
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                    if (finished.get()) {
                        stopListening()
                        return
                    }
                    update.progressInfo?.let { p ->
                        if (p.totalBytesToDownload > 0) {
                            onPreparing(((100L * p.bytesDownloaded) / p.totalBytesToDownload).toInt().coerceIn(0, 100))
                        }
                    }
                    when (update.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                            stopListening()
                            moduleInstallResult = "completed"
                            log("module installation completed")
                            verifyThenLaunch()
                        }
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                        ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                            stopListening()
                            val err = runCatching { update.errorCode }.getOrNull()
                            moduleInstallResult = "failed (state=" + update.installState + ", errorCode=" + err + ")"
                            fail(
                                "module install",
                                ScannerUnavailableException(
                                    "تعذر تنزيل ماسح Google (تحقق من الإنترنت ومتجر Play)",
                                    "Could not download the Google scanner (check internet / Play Store)"
                                )
                            )
                        }
                        else -> Unit
                    }
                }
            }
            installListener = listener
            val request = ModuleInstallRequest.newBuilder()
                .addApi(s)
                .setListener(listener)
                .build()
            main.postDelayed(installTimeout, INSTALL_TIMEOUT_MS)
            client.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) {
                        stopListening()
                        moduleInstallResult = "already installed"
                        log("module already installed")
                        verifyThenLaunch()
                    } else {
                        log("module download in progress, waiting for completion")
                    }
                }
                .addOnFailureListener { e ->
                    stopListening()
                    moduleInstallResult = "installModules failed: " + e.javaClass.name + " " + e.message
                    fail("ModuleInstallClient.installModules", e)
                }
        }

        /** After installation: confirm availability, then retry getStartScanIntent (the final judge). */
        private fun verifyThenLaunch() {
            if (abandoned()) return
            val s = scanner ?: return
            ModuleInstall.getClient(appContext).areModulesAvailable(s)
                .addOnSuccessListener { r ->
                    log("availability after install: " + r.areModulesAvailable())
                    if (r.areModulesAvailable() || launchAttempts < MAX_LAUNCH_ATTEMPTS) {
                        main.postDelayed({ launch() }, if (r.areModulesAvailable()) 0L else RETRY_DELAY_MS)
                    } else {
                        fail("areModulesAvailable", IllegalStateException("Scanner module not available after installation"))
                    }
                }
                .addOnFailureListener { e ->
                    log("availability check failed: " + e.javaClass.name + " " + e.message)
                    launch()
                }
        }

        private fun stopListening() {
            main.removeCallbacks(installTimeout)
            val c = installClient
            val l = installListener
            installListener = null
            if (c != null && l != null) runCatching { c.unregisterListener(l) }
        }

        /** Delivers exactly once, only to a live screen; waits for ON_START if the app is in background. */
        private fun deliver(block: () -> Unit) {
            if (abandoned()) return
            val owner = activityRef.get() as? LifecycleOwner
            if (owner == null || owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (finished.compareAndSet(false, true)) block()
                return
            }
            log("activity not started -> delivery deferred until ON_START")
            owner.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            source.lifecycle.removeObserver(this)
                            if (!abandoned() && finished.compareAndSet(false, true)) block()
                        }
                        Lifecycle.Event.ON_DESTROY -> {
                            source.lifecycle.removeObserver(this)
                            finished.set(true)
                            log("activity destroyed while waiting -> no callback")
                        }
                        else -> Unit
                    }
                }
            })
        }

        private fun fail(stage: String, e: Throwable) {
            if (finished.get()) return
            stopListening()
            recordFailure(stage, e)
            val described = describe(e)
            deliver { onError(described) }
        }

        private fun describe(e: Throwable): ScannerUnavailableException {
            if (e is ScannerUnavailableException) return e
            if (e is MlKitException && e.errorCode == MlKitException.UNSUPPORTED) {
                return ScannerUnavailableException(
                    "هذا الجهاز لا يدعم ماسح Google",
                    "This device does not support the Google scanner", e
                )
            }
            if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                return ScannerUnavailableException(
                    "ماسح Google غير متاح حالياً على هذا الجهاز",
                    "The Google scanner is currently unavailable on this device", e
                )
            }
            // Plain concatenation (no string template): the reason can never be shown as a literal placeholder.
            val reason = StringBuilder(e.javaClass.simpleName)
            if (e is MlKitException) reason.append(" #").append(e.errorCode)
            val msg = e.message
            if (!msg.isNullOrBlank()) reason.append(": ").append(msg.take(120))
            return ScannerUnavailableException(
                "تعذر تشغيل ماسح Google: " + reason + " (التفاصيل: الإعدادات ← التشخيص)",
                "Could not start the Google scanner: " + reason + " (details: Settings > Diagnostics)",
                e
            )
        }

        /** Full report for a developer (filesDir/crash/scanner_*.txt, listed in Settings > Diagnostics). */
        private fun recordFailure(stage: String, e: Throwable) {
            runCatching {
                val ctx = appContext
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                val gmsInstalled = runCatching {
                    val pi = ctx.packageManager.getPackageInfo("com.google.android.gms", 0)
                    pi.versionName + " (" + PackageInfoCompat.getLongVersionCode(pi) + ")"
                }.getOrElse { "not readable: " + it.javaClass.simpleName }
                val gmsStatus = runCatching {
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx).toString()
                }.getOrElse { "error " + it.javaClass.simpleName }
                val a = activityRef.get()
                val activityState = if (a == null) "collected (null)" else
                    "finishing=" + a.isFinishing + ", destroyed=" + a.isDestroyed +
                        ", changingConfigurations=" + a.isChangingConfigurations +
                        ", lifecycle=" + ((a as? LifecycleOwner)?.lifecycle?.currentState ?: "n/a")
                val causes = StringBuilder()
                var c: Throwable? = e.cause
                var depth = 0
                while (c != null && depth < 5) {
                    causes.append("  ").append(c.javaClass.name).append(": ").append(c.message).append('\n')
                    c = c.cause
                    depth++
                }
                val report = buildString {
                    appendLine("MS Scanner - Google document scanner failure")
                    appendLine("Time: " + stamp)
                    appendLine("Failed stage: " + stage)
                    appendLine("Exception class: " + e.javaClass.name)
                    appendLine("MlKitException.errorCode: " + ((e as? MlKitException)?.errorCode ?: "n/a"))
                    appendLine("Message: " + (e.message ?: "(none)"))
                    appendLine("Cause chain:")
                    append(if (causes.isEmpty()) "  (none)\n" else causes)
                    appendLine("App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + "), buildType=" +
                        BuildConfig.BUILD_TYPE + ", debug=" + BuildConfig.DEBUG)
                    appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
                    appendLine("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                    appendLine("Google Play services: installed=" + gmsInstalled + ", availabilityStatus=" + gmsStatus +
                        ", clientLibVersion=" + GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE)
                    appendLine("App locale: " + ctx.resources.configuration.locales[0] +
                        ", system locale: " + Resources.getSystem().configuration.locales[0])
                    appendLine("Activity state: " + activityState)
                    appendLine("Scanner config: mode=FULL, formats=JPEG, galleryImport=false, pageLimit=" + (pageLimit ?: "none"))
                    appendLine("Module install requested: " + moduleInstallRequested + ", result: " + moduleInstallResult)
                    appendLine("Launch attempts: " + launchAttempts + " / " + MAX_LAUNCH_ATTEMPTS)
                    appendLine()
                    appendLine("Timeline:")
                    timeline.forEach { appendLine("  " + it) }
                    appendLine()
                    appendLine("Stack trace:")
                    append(trace)
                }
                val dir = File(ctx.filesDir, "crash").apply { mkdirs() }
                File(dir, "scanner_" + stamp + ".txt").writeText(report)
                dir.listFiles { f -> f.name.startsWith("scanner_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
                Log.e(TAG, report)
            }
        }
    }
}

/** The Activity behind a Compose LocalContext (which may be wrapped). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
