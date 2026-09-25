package com.example.engine.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    
    private const val GITHUB_API_URL = "https://api.github.com/repos/MukhlidSalim/MS-Scanner/releases/latest"

    data class UpdateInfo(val version: String, val downloadUrl: String, val releaseNotes: String)

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            if (connection.responseCode == 200) {
                val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonResponse)
                val tagName = json.getString("tag_name") // e.g., "v1.0.45"
                val releaseNotes = json.getString("name")
                
                // Compare with current version
                val currentVersion = "v" + BuildConfig.VERSION_NAME
                if (tagName != currentVersion) {
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val asset = assets.getJSONObject(0)
                        val downloadUrl = asset.getString("browser_download_url")
                        return@withContext UpdateInfo(tagName, downloadUrl, releaseNotes)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("MS Scanner Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MS_Scanner_Update.apk")

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(ctx)
                    ctx.unregisterReceiver(this)
                }
            }
        }
        // Register receiver for when download is complete
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }

    private fun installApk(context: Context) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MS_Scanner_Update.apk")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        }
    }
}
