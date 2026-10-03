package com.example.engine.cv

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * One-time OpenCV native library initialization. Call once from ScannerApplication.onCreate().
 * initLocal() (available since the Maven Central AAR, 4.9.0+) loads the bundled native library
 * synchronously — no async callback, no OpenCV Manager APK dependency (unlike the old initAsync()
 * path required by the legacy SDK distribution).
 */
object OpenCvBootstrap {
    @Volatile private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            initialized = try {
                OpenCVLoader.initLocal()
            } catch (e: Throwable) {
                Log.e("OpenCvBootstrap", "OpenCV native init failed", e)
                false
            }
        }
        return initialized
    }
}
