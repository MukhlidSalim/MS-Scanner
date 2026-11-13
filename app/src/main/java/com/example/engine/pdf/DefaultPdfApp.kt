package com.example.engine.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * "Default app for PDF" support.
 */
object DefaultPdfApp {
    private const val PROBE_NAME = "ms_scanner_default_check.pdf"
    private const val EXPORT_DIR = "exports"

    sealed class Status {
        /** MS Scanner opens PDFs by default. */
        object ThisApp : Status()
        /** No default: Android asks every time (MS Scanner is offered). */
        object NotSet : Status()
        /** Another app is the default ([label] may be null when the app is not visible to MS Scanner). */
        data class OtherApp(val packageName: String?, val label: String?) : Status()
    }

    private fun viewPdfIntent(context: Context): Intent = Intent(Intent.ACTION_VIEW).apply {
        // Any content:// PDF resolves the same way; the URI is never opened.
        setDataAndType(Uri.parse("content://${context.packageName}.probe/document.pdf"), "application/pdf")
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    @Suppress("DEPRECATION")
    private fun resolveDefault(pm: PackageManager, intent: Intent): ResolveInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun handlers(pm: PackageManager, intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.getOrDefault(emptyList())

    fun status(context: Context): Status {
        val pm = context.packageManager
        val intent = viewPdfIntent(context)
        val resolved = resolveDefault(pm, intent) ?: return Status.OtherApp(null, null)
        val pkg = resolved.activityInfo?.packageName ?: return Status.NotSet
        if (pkg == context.packageName) return Status.ThisApp
        val realHandlers = handlers(pm, intent).mapNotNull { it.activityInfo?.packageName }.toSet()
        if (pkg !in realHandlers) return Status.NotSet
        val label = runCatching { resolved.loadLabel(pm)?.toString() }.getOrNull()
        return Status.OtherApp(pkg, label)
    }

    /** True for the probe PDF opened by [launchSetAsDefault] when it comes back to MS Scanner. */
    fun isProbeUri(context: Context, uri: Uri): Boolean =
        uri.authority == "${context.packageName}.provider" && (uri.lastPathSegment?.endsWith(PROBE_NAME) == true)

    /** One-page PDF used only to trigger the system "Open with … Always" dialog. */
    private fun probeFile(context: Context): File {
        val dir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, PROBE_NAME)
        if (file.exists() && file.length() > 0) return file
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 20f
                textAlign = Paint.Align.CENTER
            }
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("MS Scanner", 297f, 400f, paint)
            paint.textSize = 12f
            page.canvas.drawText("Default PDF app check", 297f, 430f, paint)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    fun launchSetAsDefault(context: Context): Boolean {
        return when (val st = status(context)) {
            is Status.OtherApp -> openClearDefaultsSettings(context, st.packageName)
            else -> runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", probeFile(context))
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    fun openClearDefaultsSettings(context: Context, otherPackage: String?): Boolean {
        val intents = buildList {
            if (!otherPackage.isNullOrBlank()) {
                add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$otherPackage")))
            }
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (i in intents) {
            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
