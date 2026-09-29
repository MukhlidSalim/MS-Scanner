package com.example.engine.scanner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google ML Kit Document Scanner (same engine as the Google Drive scanner). This is the PRIMARY capture
 * engine; the built-in camera is only a fallback.
 *
 * Root cause of "Google scanner unavailable although the device supports it": the scanner UI and models are
 * an OPTIONAL Play-services module downloaded on first use. getStartScanIntent() fails while the module is
 * missing or still downloading, and the previous code treated ANY failure as "unavailable" and opened the
 * built-in camera. Once the download finished in the background the next attempt worked, which is why the
 * behaviour looked random. Now availability is checked with ModuleInstallClient, the module is installed
 * (with progress) when missing, transient failures are retried, and only a real failure falls back.
 *
 * Returned JPEG pages enter the app's own pipeline (DocumentPipeline.processUri, no second detection,
 * no second filter) and the existing review -> save -> share flow.
 */
object GoogleDocumentScanner {

    /** Google requires about 1.7 GB of device RAM; below that the API returns UNSUPPORTED. */
    private const val MIN_TOTAL_RAM_BYTES = 1_700_000_000L
    private const val INSTALL_TIMEOUT_MS = 90_000L
    private const val MAX_LAUNCH_RETRIES = 3

    /** Scanner could not be used; [userMessageAr]/[userMessageEn] explain why (shown before the fallback). */
    class ScannerUnavailableException(
        val userMessageAr: String,
        val userMessageEn: String,
        cause: Throwable? = null
    ) : Exception(userMessageEn, cause)

    /**
     * Device capability only (Google Play services present + enough RAM). A missing / outdated scanner
     * MODULE is not a reason to skip Google: [start] installs it.
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
     * Opens the Google scanner. Order: module available? -> launch. Missing -> install (progress through
     * [onPreparing], 0..100 or null when unknown) -> launch. Transient launch failures are retried.
     * Exactly one of [onIntent] / [onError] is called, on the main thread.
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
        fun fail(e: ScannerUnavailableException) {
            if (finished.compareAndSet(false, true)) main.post { onError(e) }
        }
        val scanner = try {
            GmsDocumentScanning.getClient(options(pageLimit))
        } catch (e: Exception) {
            fail(describe(e)); return
        }
        val moduleClient = ModuleInstall.getClient(activity)

        fun launch(attempt: Int) {
            if (finished.get()) return
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    if (finished.compareAndSet(false, true)) onIntent(sender)
                }
                .addOnFailureListener { e ->
                    val transient = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
                    if (transient && attempt < MAX_LAUNCH_RETRIES) {
                        onPreparing(null)
                        main.postDelayed({ launch(attempt + 1) }, 1500L * (attempt + 1))
                    } else {
                        fail(describe(e))
                    }
                }
        }

        fun install() {
            onPreparing(0)
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                    update.progressInfo?.let { p ->
                        if (p.totalBytesToDownload > 0) onPreparing((100L * p.bytesDownloaded / p.totalBytesToDownload).toInt())
                    }
                    when (update.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                            moduleClient.unregisterListener(this)
                            launch(0)
                        }
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                        ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                            moduleClient.unregisterListener(this)
                            fail(
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
            val request = ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .setListener(listener)
                .build()
            moduleClient.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) {
                        moduleClient.unregisterListener(listener)
                        launch(0)
                    }
                    // Otherwise STATE_COMPLETED on the listener continues the flow.
                }
                .addOnFailureListener {
                    moduleClient.unregisterListener(listener)
                    launch(0) // last attempt: the scanner itself may still be able to start
                }
            main.postDelayed({
                if (!finished.get()) {
                    moduleClient.unregisterListener(listener)
                    fail(
                        ScannerUnavailableException(
                            "تنزيل ماسح Google يستغرق وقتاً أطول من المعتاد",
                            "The Google scanner download is taking too long",
                            TimeoutException()
                        )
                    )
                }
            }, INSTALL_TIMEOUT_MS)
        }

        moduleClient.areModulesAvailable(scanner)
            .addOnSuccessListener { response -> if (response.areModulesAvailable()) launch(0) else install() }
            .addOnFailureListener { launch(0) }
    }

    private fun describe(e: Exception): ScannerUnavailableException = when {
        e is ScannerUnavailableException -> e
        e is MlKitException && e.errorCode == MlKitException.UNSUPPORTED ->
            ScannerUnavailableException("هذا الجهاز لا يدعم ماسح Google", "This device does not support the Google scanner", e)
        e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE ->
            ScannerUnavailableException(
                "ماسح Google غير جاهز بعد (جاري تنزيله من خدمات Google)",
                "The Google scanner is not ready yet (being downloaded by Google Play services)", e
            )
        else -> ScannerUnavailableException(
            "تعذر تشغيل ماسح Google: ${e.localizedMessage ?: e.javaClass.simpleName}",
            "Could not start the Google scanner: ${e.localizedMessage ?: e.javaClass.simpleName}", e
        )
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
