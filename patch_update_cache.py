import os

filepath = 'app/src/main/java/com/example/engine/updater/GitHubUpdateManager.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_cache_check = """        val apkFile = File(updatesDir, "update_$cleanVer.apk")

        if (apkFile.exists()) {
            apkFile.delete()
        }

        val request = Request.Builder()"""

new_cache_check = """        val apkFile = File(updatesDir, "update_$cleanVer.apk")

        if (apkFile.exists()) {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo != null) {
                // Already downloaded and verified!
                onProgress(apkFile.length(), apkFile.length(), 1f)
                return@withContext apkFile
            } else {
                apkFile.delete() // Corrupt, delete and redownload
            }
        }

        val request = Request.Builder()"""

content = content.replace(old_cache_check, new_cache_check)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
