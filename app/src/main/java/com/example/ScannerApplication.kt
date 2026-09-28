package com.example

import android.app.Application
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScannerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // scanner warm-up without crashing
        applicationScope.launch {
            try {
                // Safely attempt scanner warm-up without crashing
                initializeScanner()
            } catch (e: Throwable) {
                Log.w("ScannerApp", "Scanner warm-up skipped: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun initializeScanner() {
        if (scannerInstance == null) {
            try {
                val options = GmsDocumentScannerOptions.Builder()
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setGalleryImportAllowed(true)
                    .build()
                
                scannerInstance = GmsDocumentScanning.getClient(options)
            } catch (e: Throwable) {
                Log.w("ScannerApp", "Could not initialize GmsDocumentScanning: ${e.message}")
                scannerInstance = null
            }
        }
    }

    companion object {
        @Volatile
        var instance: ScannerApplication? = null
            private set
        @Volatile
        private var scannerInstance: GmsDocumentScanner? = null

        fun getScanner(): GmsDocumentScanner? {
            if (scannerInstance == null) {
                synchronized(this) {
                    if (scannerInstance == null) {
                        instance?.initializeScanner()
                    }
                }
            }
            return scannerInstance
        }
    }
}
