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
        
        applicationScope.launch {
            try {
                // Pre-check and install the required ML Kit Document Scanner module
                val moduleInstallClient = ModuleInstall.getClient(this@ScannerApplication)
                val scannerClient = GmsDocumentScanning.getClient(GmsDocumentScannerOptions.Builder().build())
                
                val moduleInstallRequest = ModuleInstallRequest.newBuilder()
                    .addApi(scannerClient)
                    .build()

                moduleInstallClient.areModulesAvailable(scannerClient)
                    .addOnSuccessListener { response ->
                        if (!response.areModulesAvailable()) {
                            Log.d("ScannerApp", "Installing scanner module...")
                            moduleInstallClient.installModules(moduleInstallRequest)
                                .addOnSuccessListener { Log.d("ScannerApp", "Scanner module installed.") }
                                .addOnFailureListener { e -> Log.e("ScannerApp", "Failed to install module", e) }
                        }
                    }
                    .addOnFailureListener { e -> Log.e("ScannerApp", "Failed to check modules", e) }

                Log.d("ScannerApp", "Starting GmsDocumentScanner warm-up...")
                initializeScanner()
                Log.d("ScannerApp", "GmsDocumentScanner warm-up completed.")
            } catch (e: Exception) {
                Log.e("ScannerApp", "Error in application initialization", e)
            }
        }
    }

    @Synchronized
    private fun initializeScanner() {
        if (scannerInstance == null) {
            val options = GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setGalleryImportAllowed(true)
                .build()
            
            scannerInstance = GmsDocumentScanning.getClient(options)
        }
    }

    companion object {
        @Volatile
        private var instance: ScannerApplication? = null
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
