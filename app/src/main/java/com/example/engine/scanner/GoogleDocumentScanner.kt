package com.example.engine.scanner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Google ML Kit Document Scanner (same engine as the Google Drive scanner): ML edge detection, auto
 * capture, perspective correction, shadow / stain / finger removal, multi-page. Runs on-device through
 * Google Play services; the app needs no camera permission for it.
 *
 * This is only the CAPTURE step. Returned JPEG pages enter the app's own pipeline
 * (DocumentPipeline.processUri, no second detection, no second filter) and the existing
 * review -> save -> share flow. When the scanner is unavailable (no Play services, low-RAM device, any
 * error) the caller falls back to the built-in camera (CameraScanScreen).
 */
object GoogleDocumentScanner {

    /** Google requires about 1.7 GB of device RAM; below that the API returns UNSUPPORTED. */
    private const val MIN_TOTAL_RAM_BYTES = 1_700_000_000L

    /** Fast pre-check (Play services + RAM). The start call can still fail: callers handle onError too. */
    fun isSupported(context: Context): Boolean {
        val playServicesOk = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        if (!playServicesOk) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem <= 0L || info.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    private fun options(singlePage: Boolean): GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            // Gallery import stays in the app (one import path through the app pipeline).
            .setGalleryImportAllowed(false)
            // JPEG only: the app builds its own PDF (OCR layer, page size, password…).
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            // FULL = crop, rotate, reorder, filters + ML cleaning (shadows, stains, fingers).
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .apply { if (singlePage) setPageLimit(1) }
            .build()

    /** Asks Google Play services for the scanner screen. [onIntent] receives the IntentSender to launch. */
    fun start(
        activity: Activity,
        singlePage: Boolean,
        onIntent: (IntentSender) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            GmsDocumentScanning.getClient(options(singlePage))
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender -> onIntent(sender) }
                .addOnFailureListener { e -> onError(e) }
        } catch (e: Exception) {
            onError(e)
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
