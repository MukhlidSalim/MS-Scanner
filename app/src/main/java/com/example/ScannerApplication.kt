package com.example

import android.app.Application
import android.os.StrictMode
import com.example.engine.cv.OpenCvBootstrap
import com.example.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registered in AndroidManifest (android:name) — initializes crash reporting and pre-warms OpenCV
 * off the main thread so the first camera frame or image warp never stalls on JNI loading.
 */
class ScannerApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        appScope.launch {
            OpenCvBootstrap.ensureInitialized()
        }
        if (BuildConfig.DEBUG) {
            // Debug builds only: log disk/network access on the main thread and leaked resources,
            // so ANR risks are found during testing (never enabled for users).
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build()
            )
        }
    }

    companion object {
        @Volatile
        var instance: ScannerApplication? = null
            private set
    }
}
