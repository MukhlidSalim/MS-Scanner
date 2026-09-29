package com.example.util

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash log (no third-party SDK, nothing leaves the device unless the user shares it).
 * Uncaught exceptions are written to filesDir/crash/ (last 10 kept) and then passed to the previous
 * handler, so the normal system crash behaviour is unchanged. Settings > Diagnostics can share them.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val MAX_REPORTS = 10

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("MS Scanner crash report")
            appendLine("Time: $stamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(dir(context), "crash_$stamp.txt").writeText(report)
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }

    fun reports(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Copies the reports into ONE text file under exports/ (shared through the FileProvider). */
    fun exportForSharing(context: Context): File? {
        val list = reports(context)
        if (list.isEmpty()) return null
        val out = File(File(context.filesDir, "exports").apply { mkdirs() }, "ms_scanner_crash_reports.txt")
        out.writeText(list.joinToString("\n\n==========\n\n") { it.readText() })
        return out
    }

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }
}
