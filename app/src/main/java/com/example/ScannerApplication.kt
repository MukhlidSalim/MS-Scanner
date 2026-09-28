package com.example

import android.app.Application

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        var instance: ScannerApplication? = null
            private set
    }
}
