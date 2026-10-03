package com.example.engine.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.repository.AppPreferences
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Periodic background update check (every 12 h, only with a network connection). When a newer release is
 * published on GitHub, a notification is shown once per version; tapping it opens the in-app update dialog
 * (download -> signature verification -> in-place install, see UpdateInstaller).
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return Result.success()
        val prefs = AppPreferences(applicationContext)
        return try {
            val info = GitHubUpdateManager(applicationContext).checkUpdate(repo)
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            if (info.isUpdateAvailable &&
                info.latestVersion != prefs.ignoredUpdateVersion &&
                info.latestVersion != prefs.lastNotifiedUpdateVersion
            ) {
                if (notify(info.latestVersion)) prefs.lastNotifiedUpdateVersion = info.latestVersion
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun notify(version: String): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        ensureChannel(ctx)
        val isArabic = ctx.resources.configuration.locales[0].language == "ar"
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_UPDATE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, 1001, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (isArabic) "تحديث جديد متوفر" else "Update available")
            .setContentText(if (isArabic) "الإصدار $version جاهز للتثبيت" else "Version $version is ready to install")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update_dialog"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 4201
        private const val WORK_NAME = "periodic_update_check"

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val isArabic = ctx.resources.configuration.locales[0].language == "ar"
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, if (isArabic) "تحديثات التطبيق" else "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        /** Idempotent: keeps the existing schedule (KEEP), so calling it on every launch is safe. */
        fun schedule(context: Context) {
            if (BuildConfig.UPDATE_REPO.isBlank()) return
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
