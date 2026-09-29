package com.example.engine.updater
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The downloaded APK is signed with a different key than the installed app (Android would refuse it). */
class UpdateSignatureMismatchException : SecurityException("Update is signed with a different key than the installed app")

/**
 * THE update system of the app (GitHub Releases, update.json fallback).
 * The former UpdateManager (placeholder URL, no verification) and AppUpdater (string version compare,
 * exported receiver, stale APK re-install) were removed.
 *
 * Download verification before the installer is launched:
 *  - HTTPS only;
 *  - package name equals this app;
 *  - versionCode strictly greater than the installed one;
 *  - signing certificate matches the installed app when the platform can read it (otherwise Android's
 *    installer still enforces it).
 */
class GitHubUpdateManager(context: Context) {
    private val context: Context = context.applicationContext
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
        // 1. GitHub Releases API
        var releaseInfo: AppUpdateInfo? = null
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$cleanSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MS-Scanner-App/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string().orEmpty()
                    if (bodyString.isNotBlank()) {
                        val json = JSONObject(bodyString)
                        val tagName = json.optString("tag_name", "").trim()
                        var downloadUrl = ""
                        var apkSize = 0L
                        val assets = json.optJSONArray("assets") ?: JSONArray()
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                        val cleanVersion = tagName.removePrefix("v").removePrefix("V")
                        releaseInfo = AppUpdateInfo(
                            latestVersion = cleanVersion.ifBlank { tagName },
                            latestVersionCode = 0,
                            releaseTitle = json.optString("name", tagName),
                            releaseNotes = json.optString("body", ""),
                            downloadUrl = downloadUrl,
                            htmlUrl = json.optString("html_url", "https://github.com/$cleanSlug/releases"),
                            apkSize = apkSize,
                            publishedAt = json.optString("published_at", ""),
                            isUpdateAvailable = downloadUrl.isNotBlank() && isVersionNewer(cleanVersion, BuildConfig.VERSION_NAME)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val info = releaseInfo
        if (info != null && info.downloadUrl.isNotBlank()) return@withContext info

        // 2. Fallback: raw update.json on main branch (rate-limit resistant)
        try {
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/$cleanSlug/main/update.json")
                .header("User-Agent", "MS-Scanner-App/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string().orEmpty()
                    if (bodyString.isNotBlank()) {
                        val json = JSONObject(bodyString)
                        val versionName = json.optString("versionName", "").trim()
                        val versionCode = json.optInt("versionCode", 0)
                        val apkUrl = json.optString("apkUrl", "")
                        val isNewer = apkUrl.isNotBlank() && (isVersionNewer(versionName, BuildConfig.VERSION_NAME) ||
                            (versionCode > BuildConfig.VERSION_CODE && versionCode > 0))
                        return@withContext AppUpdateInfo(
                            latestVersion = versionName,
                            latestVersionCode = versionCode,
                            releaseTitle = json.optString("title", "Update v$versionName"),
                            releaseNotes = json.optString("notes", ""),
                            downloadUrl = apkUrl,
                            htmlUrl = "https://github.com/$cleanSlug",
                            apkSize = json.optLong("size", 0L),
                            isUpdateAvailable = isNewer
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releaseInfo ?: AppUpdateInfo(
            latestVersion = BuildConfig.VERSION_NAME,
            latestVersionCode = BuildConfig.VERSION_CODE,
            isUpdateAvailable = false
        )
    }

    /**
     * Downloads the APK with progress reporting and verifies it before returning.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        targetVersion: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
            throw SecurityException("Refusing non-HTTPS update URL")
        }
        val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        // Older update files are never re-installed by mistake.
        val cleanVer = targetVersion.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        updatesDir.listFiles()?.forEach { if (it.name != "update_$cleanVer.apk") it.delete() }
        val apkFile = File(updatesDir, "update_$cleanVer.apk")
        if (apkFile.exists()) {
            if (runCatching { verifyApk(apkFile) }.isSuccess) {
                onProgress(apkFile.length(), apkFile.length(), 1f)
                return@withContext apkFile
            }
            apkFile.delete()
        }
        val partFile = File(updatesDir, "update_$cleanVer.apk.part")
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "MS-Scanner-App/${BuildConfig.VERSION_NAME}")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed with HTTP code ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                            onProgress(downloadedBytes, totalBytes, progress)
                        }
                        output.flush()
                    }
                }
            }
            if (!partFile.renameTo(apkFile)) throw IOException("Could not finalize the downloaded file")
        } finally {
            partFile.delete()
        }
        try {
            verifyApk(apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
        apkFile
    }

    /** Throws when the APK is not a valid, newer build of THIS app signed with the same key. */
    private fun verifyApk(apkFile: File) {
        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IllegalStateException("Downloaded file is not a valid APK")
        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("Downloaded APK belongs to another app (${archiveInfo.packageName})")
        }
        if (PackageInfoCompat.getLongVersionCode(archiveInfo) <= BuildConfig.VERSION_CODE) {
            throw IllegalStateException("Downloaded APK is not newer than the installed version")
        }
        val archiveCerts = archiveDigests(apkFile)
        val installedCerts = installedDigests()
        if (archiveCerts != null && installedCerts != null && archiveCerts.none { it in installedCerts }) {
            throw UpdateSignatureMismatchException()
        }
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun digests(info: PackageInfo?): Set<String>? {
        if (info == null) return null
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return null
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        if (signatures == null || signatures.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun installedDigests(): Set<String>? = runCatching {
        digests(context.packageManager.getPackageInfo(context.packageName, signingFlags()))
    }.getOrNull()

    private fun archiveDigests(apk: File): Set<String>? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
        info?.applicationInfo?.let {
            it.sourceDir = apk.absolutePath
            it.publicSourceDir = apk.absolutePath
        }
        digests(info)
    }.getOrNull()

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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
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
