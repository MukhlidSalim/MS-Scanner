package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

object UpdateManager {

    // IMPORTANT: Replace this with the URL to your version.json file
    private const val VERSION_URL = "https://gist.githubusercontent.com/username/gist_id/raw/version.json"

    data class VersionInfo(val versionCode: Int, val downloadUrl: String)

    suspend fun checkForUpdates(context: Context): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val json = URL(VERSION_URL).readText()
            val obj = JSONObject(json)
            val latestVersionCode = obj.getInt("versionCode")
            val downloadUrl = obj.getString("downloadUrl")

            if (latestVersionCode > BuildConfig.VERSION_CODE) {
                return@withContext VersionInfo(latestVersionCode, downloadUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun downloadAndInstall(context: Context, downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, "update.apk")
            URL(downloadUrl).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            installApk(context, file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                return
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
