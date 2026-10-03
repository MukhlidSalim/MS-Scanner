package com.example.engine.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a verified update APK with the PackageInstaller session API (best practice for apps that
 * update themselves outside Google Play):
 *  - The new version REPLACES the installed app in place: same package name + same signing key, so data
 *    is kept and no uninstall is ever needed.
 *  - Android 12+: USER_ACTION_NOT_REQUIRED. After the first update installed by the app itself, the app
 *    becomes its own "installer of record" and later updates install without a confirmation screen.
 *    The very first one (app originally installed from a browser / file manager) still asks once.
 *  - Older versions: the system confirmation screen is shown (Android requirement).
 * Status is reported through [events]; MainActivity opens the confirmation screen when requested.
 */
object UpdateInstaller {

    sealed class InstallEvent {
        data class NeedsConfirmation(val intent: Intent) : InstallEvent()
        object Success : InstallEvent()
        data class Failed(val status: Int, val message: String, val signatureMismatch: Boolean) : InstallEvent()
    }

    private const val ACTION_STATUS = "com.example.engine.updater.INSTALL_STATUS"

    private val _events = MutableStateFlow<InstallEvent?>(null)
    val events: StateFlow<InstallEvent?> = _events.asStateFlow()

    fun consume() {
        _events.value = null
    }

    /** Streams the APK into an install session and commits it. Call off the main thread (it is suspend). */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val statusIntent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
                // Mutable: the system adds the status extras. Explicit component, so this is safe on API 34+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    internal fun onStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        _events.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) InstallEvent.NeedsConfirmation(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else InstallEvent.Failed(status, "Missing confirmation intent", false)
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvent.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> null // user pressed Cancel: nothing to report
            else -> InstallEvent.Failed(
                status,
                message,
                signatureMismatch = status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                    message.contains("signature", ignoreCase = true)
            )
        }
    }
}

/** Receives PackageInstaller session results (not exported: only the system/installer can target it). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstaller.onStatus(intent)
    }
}
