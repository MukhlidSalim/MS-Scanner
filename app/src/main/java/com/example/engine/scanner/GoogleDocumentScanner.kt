package com.example.engine.scanner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google ML Kit Document Scanner (same engine as the Google Drive scanner). PRIMARY capture engine; the
 * built-in camera is only a fallback.
 *
 * Fix for "Could not start the Google scanner: NullPointerException":
 *  - The NullPointerException had no message, which is the signature of a Google Play services
 *    Preconditions.checkNotNull() failure inside the optional-module layer (ModuleInstallClient), not of
 *    the scanner itself. The previous version ALWAYS went through ModuleInstall (areModulesAvailable /
 *    installModules with a status listener) before opening the scanner, so a failure there replaced a
 *    scanner that was actually available. The official integration calls getStartScanIntent() directly: the
 *    scanner downloads its own module when needed. That is now the first and normal path; ModuleInstall is
 *    only used as a recovery step when the scanner itself reports UNAVAILABLE.
 *  - The scanner is started only from a live Activity (not finishing / destroyed). Changing the app language
 *    recreates the Activity; an attempt made during that window is retried instead of falling back.
 *  - Transient failures (UNAVAILABLE, NullPointerException, IllegalStateException) are retried with backoff
 *    before the built-in camera is used.
 *  - Every real failure is written with its FULL stack trace, device, Android version, app locale and Play
 *    services version to filesDir/crash/ (Settings > Diagnostics > Share), so the next failure, if any, can
 *    be diagnosed from the exact Google frame that failed instead of a short toast.
 */
object GoogleDocumentScanner {
    private const val TAG = "GoogleDocScanner"
    /** Google requires about 1.7 GB of device RAM; below that the API returns UNSUPPORTED. */
    private const val MIN_TOTAL_RAM_BYTES = 1_700_000_000L
    private const val MAX_LAUNCH_RETRIES = 3

    /** Scanner could not be used; [userMessageAr]/[userMessageEn] explain why (shown before the fallback). */
    class ScannerUnavailableException(
        val userMessageAr: String,
        val userMessageEn: String,
        cause: Throwable? = null
    ) : Exception(userMessageEn, cause)

    /**
     * Device capability only (Google Play services present + enough RAM). A missing / outdated scanner
     * MODULE is not a reason to skip Google: the scanner installs it itself.
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

    private fun isAlive(a: Activity): Boolean =
        !a.isFinishing && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && a.isDestroyed)

    private fun isTransient(e: Throwable): Boolean =
        (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) ||
            e is NullPointerException || e is IllegalStateException

    /**
     * Opens the Google scanner. getStartScanIntent() is called directly (the official path). Transient failures
     * are retried; UNAVAILABLE additionally asks Play services to install the scanner module (progress through
     * [onPreparing]). Exactly one of [onIntent] / [onError] is called, on the main thread.
     */
    fun start(
        activity: Activity,
        pageLimit: Int?,
        onPreparing: (progressPercent: Int?) -> Unit,
        onIntent: (IntentSender) -> Unit,
        onError: (ScannerUnavailableException) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val appContext = activity.applicationContext
        var moduleInstallRequested = false

        fun fail(e: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            recordFailure(appContext, e)
            val described = describe(e)
            main.post { onError(described) }
        }

        val scanner = try {
            GmsDocumentScanning.getClient(options(pageLimit))
        } catch (e: Throwable) {
            fail(e); return
        }

        fun requestModuleInstall() {
            if (moduleInstallRequested) return
            moduleInstallRequested = true
            runCatching {
                // No status listener: the listener path is where the Play services precondition failed.
                ModuleInstall.getClient(appContext)
                    .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                    .addOnFailureListener { Log.w(TAG, "installModules failed", it) }
            }.onFailure { Log.w(TAG, "installModules threw", it) }
        }

        fun launch(attempt: Int) {
            if (finished.get()) return
            if (!isAlive(activity)) {
                // Activity being recreated (e.g. language change): the caller relaunches on the new instance.
                if (attempt < MAX_LAUNCH_RETRIES) {
                    main.postDelayed({ launch(attempt + 1) }, 600L)
                } else {
                    fail(IllegalStateException("Activity destroyed before the scanner could start"))
                }
                return
            }
            val task = try {
                scanner.getStartScanIntent(activity)
            } catch (e: Throwable) {
                if (isTransient(e) && attempt < MAX_LAUNCH_RETRIES) {
                    Log.w(TAG, "getStartScanIntent threw (attempt $attempt), retrying", e)
                    main.postDelayed({ launch(attempt + 1) }, 800L * (attempt + 1))
                } else {
                    fail(e)
                }
                return
            }
            task.addOnSuccessListener { sender ->
                if (finished.compareAndSet(false, true)) onIntent(sender)
            }.addOnFailureListener { e ->
                if (isTransient(e) && attempt < MAX_LAUNCH_RETRIES) {
                    Log.w(TAG, "getStartScanIntent failed (attempt $attempt), retrying", e)
                    if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                        onPreparing(null)
                        requestModuleInstall()
                    }
                    main.postDelayed({ launch(attempt + 1) }, 1500L * (attempt + 1))
                } else {
                    fail(e)
                }
            }
        }

        main.post { launch(0) }
    }

    private fun describe(e: Throwable): ScannerUnavailableException {
        val where = e.stackTrace.firstOrNull { it.className.startsWith("com.google") }
            ?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
        val reason = (e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName) + where
        return when {
            e is ScannerUnavailableException -> e
            e is MlKitException && e.errorCode == MlKitException.UNSUPPORTED ->
                ScannerUnavailableException("هذا الجهاز لا يدعم ماسح Google", "This device does not support the Google scanner", e)
            e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE ->
                ScannerUnavailableException(
                    "ماسح Google غير جاهز بعد (جاري تنزيله من خدمات Google)",
                    "The Google scanner is not ready yet (being downloaded by Google Play services)", e
                )
            else -> ScannerUnavailableException(
                "تعذر تشغيل ماسح Google: $reason (التفاصيل: الإعدادات ← التشخيص)",
                "Could not start the Google scanner: $reason (details: Settings > Diagnostics)", e
            )
        }
    }

    /** Full diagnostic report next to the crash reports (shared from Settings > Diagnostics). */
    private fun recordFailure(context: Context, e: Throwable) {
        runCatching {
            val dir = File(context.filesDir, "crash").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            val gmsVersion = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
            }.getOrDefault("unknown")
            val report = buildString {
                appendLine("MS Scanner - Google document scanner failure")
                appendLine("Time: $stamp")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("App locale: ${context.resources.configuration.locales[0]}  System locale: ${Locale.getDefault()}")
                appendLine("Google Play services: $gmsVersion")
                appendLine()
                append(trace)
            }
            File(dir, "scanner_$stamp.txt").writeText(report)
            dir.listFiles { f -> f.name.startsWith("scanner_") }?.sortedByDescending { it.lastModified() }
                ?.drop(5)?.forEach { it.delete() }
            Log.e(TAG, report)
        }
    }

    /** Page image URIs of a successful scan (empty when cancelled or nothing was scanned). */
    fun pageUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK) return emptyList()
        return runCatching {
            GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.map { it.imageUri }.orEmpty()
        }.getOrDefault(emptyList())
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
