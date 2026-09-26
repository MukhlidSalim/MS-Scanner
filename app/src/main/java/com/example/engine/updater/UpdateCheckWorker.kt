package com.example.engine.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.AppPreferences

class UpdateCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val updateManager = GitHubUpdateManager(applicationContext)
        
        return try {
            val info = updateManager.checkUpdate(prefs.githubRepoSlug)
            if (info.isUpdateAvailable) {
                // We could trigger a notification here if desired.
                // For now, updating prefs is enough to be picked up by the UI next time it opens.
                prefs.lastUpdateCheckTime = System.currentTimeMillis()
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
