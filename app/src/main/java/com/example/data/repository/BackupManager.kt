package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.db.DocumentDao
import com.example.data.model.DocumentEntity
import com.example.data.model.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val documentDao: DocumentDao
) {
    suspend fun createBackup(outputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val docs = documentDao.getAllDocumentsSync()
            var docCount = 0
            
            context.contentResolver.openOutputStream(outputUri)?.use { outStream ->
                ZipOutputStream(outStream).use { zos ->
                    // 1. Manifest
                    val manifest = JSONObject().apply {
                        put("version", 1)
                        put("app_version", "1.0")
                        put("timestamp", System.currentTimeMillis())
                        put("doc_count", docs.size)
                    }
                    zos.putNextEntry(ZipEntry("backup_manifest.json"))
                    zos.write(manifest.toString(4).toByteArray())
                    zos.closeEntry()
                    
                    // 2. Documents
                    for (doc in docs) {
                        val pages = documentDao.getPagesForDocumentSync(doc.id)
                        val docDir = "documents/doc_${doc.id}/"
                        
                        val docMeta = JSONObject().apply {
                            put("title", doc.title)
                            put("folderName", doc.folderName)
                            put("category", doc.category)
                            put("createdAt", doc.createdAt)
                            put("updatedAt", doc.updatedAt)
                            
                            val pagesArray = JSONArray()
                            for (page in pages) {
                                val pageObj = JSONObject().apply {
                                    put("id", page.id)
                                    put("pageIndex", page.pageIndex)
                                    put("rawImagePath", File(page.rawImagePath).name)
                                    put("processedImagePath", File(page.processedImagePath).name)
                                    put("thumbnailPath", if (page.thumbnailPath.isNotBlank()) File(page.thumbnailPath).name else "")
                                    put("ocrText", page.ocrText)
                                }
                                pagesArray.put(pageObj)
                                
                                // Copy files
                                copyFileToZip(page.rawImagePath, "${docDir}pages/${File(page.rawImagePath).name}", zos)
                                copyFileToZip(page.processedImagePath, "${docDir}pages/${File(page.processedImagePath).name}", zos)
                                if (page.thumbnailPath.isNotBlank()) {
                                    copyFileToZip(page.thumbnailPath, "${docDir}pages/${File(page.thumbnailPath).name}", zos)
                                }
                            }
                            put("pages", pagesArray)
                        }
                        
                        zos.putNextEntry(ZipEntry("${docDir}metadata.json"))
                        zos.write(docMeta.toString(4).toByteArray())
                        zos.closeEntry()
                        
                        docCount++
                    }
                }
            }
            Result.success(docCount)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
    
    private fun copyFileToZip(path: String, zipPath: String, zos: ZipOutputStream) {
        val file = File(path)
        if (!file.exists()) return
        zos.putNextEntry(ZipEntry(zipPath))
        FileInputStream(file).use { fis ->
            fis.copyTo(zos)
        }
        zos.closeEntry()
    }
    
    suspend fun restoreBackup(inputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val file = File(tempDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            
            val manifestFile = File(tempDir, "backup_manifest.json")
            if (!manifestFile.exists()) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(Exception("Invalid backup: Missing manifest"))
            }
            
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optInt("version", 0) != 1) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(Exception("Unsupported backup version"))
            }
            
            var restoredCount = 0
            val docsDir = File(tempDir, "documents")
            if (docsDir.exists()) {
                for (docFolder in docsDir.listFiles() ?: emptyArray()) {
                    if (docFolder.isDirectory) {
                        val metaFile = File(docFolder, "metadata.json")
                        if (metaFile.exists()) {
                            val meta = JSONObject(metaFile.readText())
                            val title = meta.getString("title") + " (Restored)"
                            
                            val newDocId = documentDao.insertDocument(
                                DocumentEntity(
                                    title = title,
                                    folderName = meta.getString("folderName"),
                                    category = meta.getString("category"),
                                    createdAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                    pageCount = meta.getJSONArray("pages").length(),
                                    thumbnailPath = "" // will update
                                )
                            )
                            
                            val pagesArray = meta.getJSONArray("pages")
                            var firstThumb = ""
                            for (i in 0 until pagesArray.length()) {
                                val pageObj = pagesArray.getJSONObject(i)
                                val rawName = pageObj.getString("rawImagePath")
                                val procName = pageObj.getString("processedImagePath")
                                val thumbName = pageObj.optString("thumbnailPath", "")
                                
                                val newRawPath = File(context.filesDir, "restored_${System.currentTimeMillis()}_$rawName")
                                val newProcPath = File(context.filesDir, "restored_${System.currentTimeMillis()}_$procName")
                                val newThumbPath = if (thumbName.isNotEmpty()) File(context.filesDir, "restored_${System.currentTimeMillis()}_$thumbName") else null
                                
                                File(docFolder, "pages/$rawName").copyTo(newRawPath)
                                File(docFolder, "pages/$procName").copyTo(newProcPath)
                                newThumbPath?.let { File(docFolder, "pages/$thumbName").copyTo(it) }
                                
                                if (i == 0) firstThumb = newThumbPath?.absolutePath ?: newProcPath.absolutePath
                                
                                documentDao.insertPage(
                                    PageEntity(
                                        documentId = newDocId,
                                        pageIndex = i,
                                        rawImagePath = newRawPath.absolutePath,
                                        processedImagePath = newProcPath.absolutePath,
                                        thumbnailPath = newThumbPath?.absolutePath ?: "",
                                        ocrText = pageObj.optString("ocrText", "")
                                    )
                                )
                            }
                            
                            documentDao.updateDocumentThumbnail(newDocId, firstThumb)
                            restoredCount++
                        }
                    }
                }
            }
            
            tempDir.deleteRecursively()
            Result.success(restoredCount)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
