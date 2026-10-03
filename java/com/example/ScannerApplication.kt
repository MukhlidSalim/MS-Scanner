package com.example

import android.app.Application
import android.os.StrictMode
import com.example.util.CrashReporter

/**
 * Registered in AndroidManifest (android:name) — previously it was never used, so [instance] stayed null.
 */
class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
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
