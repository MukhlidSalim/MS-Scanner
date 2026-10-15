package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.model.PageEntity
import com.example.engine.annotation.SignatureStore
import com.example.engine.cv.DocumentQuad
import com.example.engine.cv.QuadStore
import com.example.engine.ocr.DocumentAnalysisWorker
import com.example.engine.ocr.OcrLayoutStore
import com.example.engine.ocr.OcrLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * In-app backup / restore (ZIP).
 *
 * Format v2 (v1 still restorable):
 *   backup_manifest.json (includes custom folders list)
 *   signatures/sig_<id>.png + signatures/signatures.json
 *   documents/doc_<id>/metadata.json
 *   documents/doc_<id>/pages/p<index>_raw.jpg | p<index>_proc.jpg
 * Pages keep their crop quad, rotation, filter, OCR text, OCR entities, and OCR layout lines,
 * and documents preserve their original creation/modification timestamps, tags, and suggested titles.
 */
class BackupManager(
    private val context: Context,
    private val repository: DocumentRepository
) {

    suspend fun createBackup(outputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val docs = repository.getAllDocumentsSync().filter { !it.isTrash }
            val folders = runCatching { repository.observeFolders().first() }.getOrDefault(emptyList())
                .filter { !repository.isReservedFolder(it) }
            val signatures = runCatching { repository.getAllSignatures().first() }.getOrDefault(emptyList())

            var docCount = 0
            val out = context.contentResolver.openOutputStream(outputUri)
                ?: return@withContext Result.failure(IOException("Cannot open backup destination"))
            out.use { outStream ->
                ZipOutputStream(outStream).use { zos ->
                    val foldersArray = JSONArray().apply { folders.forEach { put(it) } }
                    val manifest = JSONObject().apply {
                        put("version", FORMAT_VERSION)
                        put("app_version", com.example.BuildConfig.VERSION_NAME)
                        put("timestamp", System.currentTimeMillis())
                        put("doc_count", docs.size)
                        put("folders", foldersArray)
                    }
                    zos.putNextEntry(ZipEntry(MANIFEST))
                    zos.write(manifest.toString(2).toByteArray())
                    zos.closeEntry()

                    // Backup saved signatures
                    if (signatures.isNotEmpty()) {
                        val sigArray = JSONArray()
                        signatures.forEachIndexed { idx, sig ->
                            val sigFile = File(sig.imagePath)
                            if (sigFile.isFile) {
                                val entryName = "sig_${idx}.png"
                                copyFileToZip(sig.imagePath, "signatures/$entryName", zos)
                                sigArray.put(JSONObject().apply {
                                    put("title", sig.title)
                                    put("entry", entryName)
                                    put("createdAt", sig.createdAt)
                                })
                            }
                        }
                        zos.putNextEntry(ZipEntry("signatures/signatures.json"))
                        zos.write(sigArray.toString(2).toByteArray())
                        zos.closeEntry()
                    }

                    for (doc in docs) {
                        val pages = repository.getPagesForDocumentSync(doc.id).sortedBy { it.pageIndex }
                        val docDir = "documents/doc_${doc.id}/"
                        val pagesArray = JSONArray()
                        pages.forEachIndexed { i, page ->
                            val rawEntry = "p${i}_raw.jpg"
                            val sameFile = page.processedImagePath == page.rawImagePath
                            val procEntry = if (sameFile) rawEntry else "p${i}_proc.jpg"
                            copyFileToZip(page.rawImagePath, "${docDir}pages/$rawEntry", zos)
                            if (!sameFile) copyFileToZip(page.processedImagePath, "${docDir}pages/$procEntry", zos)

                            val layoutLines = OcrLayoutStore.load(page.processedImagePath)
                            val layoutArr = JSONArray()
                            layoutLines?.forEach { l ->
                                layoutArr.put(JSONObject().apply {
                                    put("t", l.text)
                                    put("l", l.left.toDouble())
                                    put("tp", l.top.toDouble())
                                    put("r", l.right.toDouble())
                                    put("b", l.bottom.toDouble())
                                })
                            }

                            pagesArray.put(JSONObject().apply {
                                put("pageIndex", i)
                                put("rawEntry", rawEntry)
                                put("procEntry", procEntry)
                                put("rotationDegrees", page.rotationDegrees)
                                put("filterType", page.filterType)
                                put("cropQuadJson", page.cropQuadJson.ifBlank { QuadStore.load(page.rawImagePath)?.toJson() ?: "" })
                                put("ocrText", page.ocrText)
                                put("ocrEntitiesJson", page.ocrEntitiesJson)
                                put("qualityStatus", page.qualityStatus)
                                put("createdAt", page.createdAt)
                                if (layoutArr.length() > 0) put("ocrLayout", layoutArr)
                            })
                        }
                        val meta = JSONObject().apply {
                            put("title", doc.title)
                            put("folderName", doc.folderName)
                            put("category", doc.category)
                            put("isFavorite", doc.isFavorite)
                            put("createdAt", doc.createdAt)
                            put("updatedAt", doc.updatedAt)
                            put("suggestedTitle", doc.suggestedTitle)
                            put("tagsCsv", doc.tagsCsv)
                            put("ocrText", doc.ocrText)
                            put("pages", pagesArray)
                        }
                        zos.putNextEntry(ZipEntry("${docDir}metadata.json"))
                        zos.write(meta.toString(2).toByteArray())
                        zos.closeEntry()
                        docCount++
                    }
                }
            }
            Result.success(docCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun copyFileToZip(path: String, zipPath: String, zos: ZipOutputStream) {
        val file = File(path)
        if (path.isBlank() || !file.exists()) return
        zos.putNextEntry(ZipEntry(zipPath))
        FileInputStream(file).use { fis -> fis.copyTo(zos) }
        zos.closeEntry()
    }

    suspend fun restoreBackup(inputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IOException("Cannot open backup file"))
            input.use { extractSafely(ZipInputStream(it), tempDir) }

            val manifestFile = File(tempDir, MANIFEST)
            if (!manifestFile.isFile) return@withContext Result.failure(Exception("Invalid backup: missing manifest"))
            val manifestJson = JSONObject(manifestFile.readText())
            val version = manifestJson.optInt("version", 0)
            if (version !in 1..FORMAT_VERSION) return@withContext Result.failure(Exception("Unsupported backup version $version"))

            // Restore custom folders if present
            val foldersArr = manifestJson.optJSONArray("folders")
            if (foldersArr != null) {
                for (i in 0 until foldersArr.length()) {
                    val folderName = foldersArr.optString(i).trim()
                    if (folderName.isNotBlank() && !repository.isReservedFolder(folderName)) {
                        runCatching { repository.createFolder(folderName) }
                    }
                }
            }

            // Restore saved signatures if present
            val sigJsonFile = File(tempDir, "signatures/signatures.json")
            if (sigJsonFile.isFile) {
                val sigArr = runCatching { JSONArray(sigJsonFile.readText()) }.getOrNull()
                if (sigArr != null) {
                    val sigDir = File(context.filesDir, "signatures").apply { mkdirs() }
                    for (i in 0 until sigArr.length()) {
                        val sObj = sigArr.optJSONObject(i) ?: continue
                        val entryName = File(sObj.optString("entry")).name
                        val srcSig = File(tempDir, "signatures/$entryName")
                        if (entryName.isNotBlank() && srcSig.isFile) {
                            val dstSig = File(sigDir, "sig_restored_${UUID.randomUUID().toString().take(8)}.png")
                            srcSig.copyTo(dstSig, overwrite = true)
                            repository.saveSignature(sObj.optString("title", "Signature"), dstSig.absolutePath)
                        }
                    }
                }
            }

            var restoredCount = 0
            val docsDir = File(tempDir, "documents")
            for (docFolder in docsDir.listFiles().orEmpty().sortedBy { it.name }) {
                if (!docFolder.isDirectory) continue
                val metaFile = File(docFolder, "metadata.json")
                if (!metaFile.isFile) continue
                if (restoreDocument(JSONObject(metaFile.readText()), File(docFolder, "pages"), version)) restoredCount++
            }
            Result.success(restoredCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Extracts only regular entries that stay inside [target]; enforces size and count limits. */
    private fun extractSafely(zis: ZipInputStream, target: File) {
        val root = target.canonicalFile
        var total = 0L
        var count = 0
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val entry = zis.nextEntry ?: break
            if (++count > MAX_ENTRIES) throw SecurityException("Backup contains too many entries")
            val name = entry.name.replace('\\', '/')
            if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                throw SecurityException("Blocked unsafe path in backup: $name")
            }
            val outFile = File(root, name).canonicalFile
            if (!outFile.path.startsWith(root.path + File.separator)) {
                throw SecurityException("Blocked unsafe path in backup: $name")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
                continue
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                while (true) {
                    val read = zis.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TOTAL_BYTES) throw SecurityException("Backup is too large")
                    fos.write(buffer, 0, read)
                }
            }
        }
    }

    /** Copies one document's pages into app storage and saves it through the single transaction. */
    private suspend fun restoreDocument(meta: JSONObject, pagesDir: File, version: Int): Boolean {
        val pagesArray = meta.optJSONArray("pages") ?: return false
        val scansDir = File(context.filesDir, "scans").apply { mkdirs() }
        val createdFiles = mutableListOf<File>()
        val pages = mutableListOf<PageEntity>()
        var anyMissingOcrLayout = false
        try {
            for (i in 0 until pagesArray.length()) {
                val p = pagesArray.optJSONObject(i) ?: continue
                val rawName = File(if (version >= 2) p.optString("rawEntry") else p.optString("rawImagePath")).name
                val procName = File(if (version >= 2) p.optString("procEntry") else p.optString("processedImagePath")).name
                val rawSrc = File(pagesDir, rawName).takeIf { rawName.isNotBlank() && it.isFile }
                val procSrc = File(pagesDir, procName).takeIf { procName.isNotBlank() && it.isFile }
                val source = rawSrc ?: procSrc ?: continue
                val tag = UUID.randomUUID().toString().take(8)
                val newRaw = File(scansDir, "restored_raw_${tag}.jpg")
                source.copyTo(newRaw, overwrite = true)
                createdFiles += newRaw
                val newProc = if (procSrc != null && procSrc != source) {
                    File(scansDir, "restored_proc_${tag}.jpg").also { procSrc.copyTo(it, overwrite = true); createdFiles += it }
                } else newRaw

                val quadJson = p.optString("cropQuadJson", "")
                DocumentQuad.fromJsonOrNull(quadJson)?.let { QuadStore.save(newRaw.absolutePath, it) }

                val layoutArr = p.optJSONArray("ocrLayout")
                if (layoutArr != null && layoutArr.length() > 0) {
                    val lines = (0 until layoutArr.length()).mapNotNull { idx ->
                        val o = layoutArr.optJSONObject(idx) ?: return@mapNotNull null
                        OcrLine(
                            o.optString("t"),
                            o.optDouble("l").toFloat(),
                            o.optDouble("tp").toFloat(),
                            o.optDouble("r").toFloat(),
                            o.optDouble("b").toFloat()
                        ).takeIf { it.text.isNotBlank() }
                    }
                    if (lines.isNotEmpty()) OcrLayoutStore.save(newProc.absolutePath, lines)
                } else {
                    anyMissingOcrLayout = true
                }

                pages += PageEntity(
                    documentId = 0L,
                    pageIndex = pages.size,
                    rawImagePath = newRaw.absolutePath,
                    processedImagePath = newProc.absolutePath,
                    rotationDegrees = p.optInt("rotationDegrees", 0),
                    filterType = p.optString("filterType", "").ifBlank { "ORIGINAL" },
                    cropQuadJson = quadJson,
                    ocrText = p.optString("ocrText", ""),
                    ocrEntitiesJson = p.optString("ocrEntitiesJson", ""),
                    qualityStatus = p.optString("qualityStatus", "GOOD").ifBlank { "GOOD" },
                    createdAt = p.optLong("createdAt", System.currentTimeMillis())
                )
            }
            if (pages.isEmpty()) return false
            val folderName = meta.optString("folderName", "Default").ifBlank { "Default" }
            if (!repository.isReservedFolder(folderName)) {
                runCatching { repository.createFolder(folderName) }
            }
            val rawTitle = meta.optString("title", "Document").removeSuffix(" (Restored)").removeSuffix(" (مستعاد)").ifBlank { "Document" }
            val docId = repository.saveDocument(
                DocumentSaveRequest(
                    existingDocId = null,
                    title = rawTitle,
                    folderName = folderName,
                    category = meta.optString("category", "OTHER").ifBlank { "OTHER" },
                    pages = pages
                )
            )
            val now = System.currentTimeMillis()
            val origCreatedAt = meta.optLong("createdAt", now).takeIf { it > 0L } ?: now
            val origUpdatedAt = meta.optLong("updatedAt", origCreatedAt).takeIf { it > 0L } ?: origCreatedAt
            repository.getDocumentById(docId)?.let { doc ->
                repository.updateDocument(
                    doc.copy(
                        isFavorite = meta.optBoolean("isFavorite", false),
                        createdAt = origCreatedAt,
                        updatedAt = origUpdatedAt,
                        suggestedTitle = meta.optString("suggestedTitle", doc.suggestedTitle),
                        tagsCsv = meta.optString("tagsCsv", doc.tagsCsv),
                        ocrText = meta.optString("ocrText", doc.ocrText)
                    )
                )
            }
            if (anyMissingOcrLayout) {
                runCatching { DocumentAnalysisWorker.enqueue(context, docId, generatedTitle = false) }
            }
            return true
        } catch (e: CancellationException) {
            createdFiles.forEach { it.delete(); QuadStore.delete(it.absolutePath); OcrLayoutStore.delete(it.absolutePath) }
            throw e
        } catch (e: Exception) {
            createdFiles.forEach { it.delete(); QuadStore.delete(it.absolutePath); OcrLayoutStore.delete(it.absolutePath) }
            e.printStackTrace()
            return false
        }
    }

    private companion object {
        const val MANIFEST = "backup_manifest.json"
        const val FORMAT_VERSION = 2
        const val MAX_ENTRIES = 20_000
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB
    }
}
