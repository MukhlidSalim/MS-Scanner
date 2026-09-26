package com.example.engine.updater

import java.io.File

data class AppUpdateInfo(
    val latestVersion: String,
    val latestVersionCode: Int = 0,
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val htmlUrl: String = "",
    val apkSize: Long = 0L,
    val publishedAt: String = "",
    val isUpdateAvailable: Boolean = false
)

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float) : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File, val updateInfo: AppUpdateInfo) : UpdateDownloadState()
    data class PermissionRequired(val apkFile: File, val updateInfo: AppUpdateInfo) : UpdateDownloadState()
    data class Error(val messageAr: String, val messageEn: String) : UpdateDownloadState()
}

sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    data class Available(val updateInfo: AppUpdateInfo, val isManual: Boolean = false) : UpdateCheckState()
    data class UpToDate(val currentVersion: String, val isManual: Boolean = false) : UpdateCheckState()
    data class Error(val messageAr: String, val messageEn: String, val isManual: Boolean = false) : UpdateCheckState()
}
