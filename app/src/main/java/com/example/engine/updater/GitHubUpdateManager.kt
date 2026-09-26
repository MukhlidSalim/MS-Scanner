package com.example.engine.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks GitHub for the latest release or update.json manifest.
     */
    suspend fun checkUpdate(repoSlug: String): AppUpdateInfo = withContext(Dispatchers.IO) {
        val cleanSlug = repoSlug.trim().removePrefix("https://github.com/").removeSuffix("/")
        if (cleanSlug.isBlank() || !cleanSlug.contains("/")) {
            return@withContext AppUpdateInfo(
                latestVersion = BuildConfig.VERSION_NAME,
                latestVersionCode = BuildConfig.VERSION_CODE,
                isUpdateAvailable = false
            )
        }

        // 1. Try GitHub Releases API
        var releaseInfo: AppUpdateInfo? = null
        try {
            val releaseUrl = "https://api.github.com/repos/$cleanSlug/releases/latest"
            val request = Request.Builder()
                .url(releaseUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MS-Scanner-App/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isNotBlank()) {
                    val json = JSONObject(bodyString)
                    val tagName = json.optString("tag_name", "").trim()
                    val title = json.optString("name", tagName)
                    val body = json.optString("body", "")
                    val htmlUrl = json.optString("html_url", "https://github.com/$cleanSlug/releases")
                    val publishedAt = json.optString("published_at", "")

                    var downloadUrl = ""
                    var apkSize = 0L

                    val assets = json.optJSONArray("assets") ?: JSONArray()
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }

                    // Fallback to tag direct download if asset list has no explicit .apk asset
                    if (downloadUrl.isBlank() && tagName.isNotBlank()) {
                        downloadUrl = "https://github.com/$cleanSlug/releases/download/$tagName/app-release.apk"
                    }

                    val cleanVersion = tagName.removePrefix("v").removePrefix("V")
                    val isNewer = isVersionNewer(cleanVersion, BuildConfig.VERSION_NAME)

                    releaseInfo = AppUpdateInfo(
                        latestVersion = cleanVersion.ifBlank { tagName },
                        latestVersionCode = 0,
                        releaseTitle = title,
                        releaseNotes = body,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl,
                        apkSize = apkSize,
                        publishedAt = publishedAt,
                        isUpdateAvailable = isNewer
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (releaseInfo != null && releaseInfo.downloadUrl.isNotBlank()) {
            return@withContext releaseInfo
        }

        // 2. Fallback to raw update.json on main branch (Rate-limit resistant)
        try {
            val rawJsonUrl = "https://raw.githubusercontent.com/$cleanSlug/main/update.json"
            val request = Request.Builder()
                .url(rawJsonUrl)
                .header("User-Agent", "MS-Scanner-App/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isNotBlank()) {
                    val json = JSONObject(bodyString)
                    val versionName = json.optString("versionName", "").trim()
                    val versionCode = json.optInt("versionCode", 0)
                    val title = json.optString("title", "Update v$versionName")
                    val notes = json.optString("notes", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val size = json.optLong("size", 0L)

                    val isNewer = isVersionNewer(versionName, BuildConfig.VERSION_NAME) ||
                            (versionCode > BuildConfig.VERSION_CODE && versionCode > 0)

                    return@withContext AppUpdateInfo(
                        latestVersion = versionName,
                        latestVersionCode = versionCode,
                        releaseTitle = title,
                        releaseNotes = notes,
                        downloadUrl = apkUrl,
                        htmlUrl = "https://github.com/$cleanSlug",
                        apkSize = size,
                        isUpdateAvailable = isNewer
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback default
        releaseInfo ?: AppUpdateInfo(
            latestVersion = BuildConfig.VERSION_NAME,
            latestVersionCode = BuildConfig.VERSION_CODE,
            isUpdateAvailable = false
        )
    }

    /**
     * Downloads the APK file with real-time byte progress reporting.
     * Verifies package integrity before returning.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        targetVersion: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }
        val cleanVer = targetVersion.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val apkFile = File(updatesDir, "update_$cleanVer.apk")

        if (apkFile.exists()) {
            apkFile.delete()
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; MS-Scanner)")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Download failed with HTTP code ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()

        var downloadedBytes = 0L
        val buffer = ByteArray(8 * 1024)

        body.byteStream().use { input ->
            FileOutputStream(apkFile).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                    onProgress(downloadedBytes, totalBytes, progress)
                }
                output.flush()
            }
        }

        // Verify package integrity using Android PackageManager
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (archiveInfo == null) {
            apkFile.delete()
            throw IllegalStateException("Downloaded APK package is corrupt or incomplete")
        }

        apkFile
    }

    /**
     * Checks if the app currently has permission to install unknown apps.
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Returns an intent to navigate to System Settings to grant unknown app install permission.
     */
    fun getInstallPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Launches the Android PackageInstaller to install the verified APK.
     */
    fun launchInstallApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    companion object {
        /**
         * Semantic Version comparison: returns true if remoteVersion > currentVersion
         */
        fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
            if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
            val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V").split("-")[0]
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V").split("-")[0]

            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
