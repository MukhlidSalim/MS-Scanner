package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.model.PageEntity
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when restoring an encrypted backup without a password or with an incorrect password. */
class BackupPasswordRequiredException(
    val wrongPassword: Boolean = false,
    message: String = if (wrongPassword) "Incorrect backup password" else "Password required to restore encrypted backup"
) : Exception(message)

/**
 * In-app backup / restore (ZIP, with optional AES-256-GCM password encryption).
 *
 * Format v2 (v1 still restorable):
 *   backup_manifest.json (includes custom folders list)
 *   signatures/sig_<id>.png + signatures/signatures.json
 *   documents/doc_<id>/metadata.json
 *   documents/doc_<id>/pages/p<index>_raw.jpg | p<index>_proc.jpg | p<index>_annot.png
 *
 * Encrypted container format (when password is provided):
 *   6-byte magic header "MSBAK1" + 16-byte PBKDF2 salt + 12-byte GCM IV + AES-256-GCM encrypted ZIP stream.
 */
class BackupManager(
    private val context: Context,
    private val repository: DocumentRepository
) {

    suspend fun createBackup(outputUri: Uri, password: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val docs = repository.getAllDocumentsSync().filter { !it.isTrash }
            val folders = runCatching { repository.observeFolders().first() }.getOrDefault(emptyList())
                .filter { !repository.isReservedFolder(it) }
            val signatures = runCatching { repository.getAllSignatures().first() }.getOrDefault(emptyList())

            var docCount = 0
            val out = context.contentResolver.openOutputStream(outputUri)
                ?: return@withContext Result.failure(IOException("Cannot open backup destination"))
            val cleanPassword = password?.takeIf { it.isNotBlank() }
            out.use { rawOutStream ->
                val targetStream = if (cleanPassword != null) {
                    wrapEncryptedOutputStream(rawOutStream, cleanPassword)
                } else {
                    rawOutStream
                }
                ZipOutputStream(targetStream).use { zos ->
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

                            val annotFile = File("${page.rawImagePath}.annot.png")
                            val annotEntry = if (annotFile.isFile) {
                                val name = "p${i}_annot.png"
                                copyFileToZip(annotFile.absolutePath, "${docDir}pages/$name", zos)
                                name
                            } else ""

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
                                if (annotEntry.isNotBlank()) put("annotEntry", annotEntry)
                                put("noStraighten", QuadStore.isNoStraighten(page.rawImagePath))
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

    suspend fun restoreBackup(inputUri: Uri, password: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rawInput = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IOException("Cannot open backup file"))
            rawInput.use { stream ->
                val buffered = BufferedInputStream(stream, 64 * 1024)
                buffered.mark(16)
                val header = ByteArray(ENCRYPTED_MAGIC.size)
                val readHeader = buffered.read(header)
                val isEncrypted = readHeader == ENCRYPTED_MAGIC.size && header.contentEquals(ENCRYPTED_MAGIC)
                val zipSourceStream: InputStream = if (isEncrypted) {
                    val cleanPwd = password?.takeIf { it.isNotBlank() }
                        ?: return@withContext Result.failure(BackupPasswordRequiredException(wrongPassword = false))
                    try {
                        unwrapEncryptedInputStream(buffered, cleanPwd)
                    } catch (e: Exception) {
                        return@withContext Result.failure(BackupPasswordRequiredException(wrongPassword = true))
                    }
                } else {
                    buffered.reset()
                    buffered
                }
                try {
                    extractSafely(ZipInputStream(zipSourceStream), tempDir)
                } catch (e: Exception) {
                    if (isEncrypted) {
                        return@withContext Result.failure(BackupPasswordRequiredException(wrongPassword = true))
                    }
                    throw e
                }
            }

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
                val annotName = File(p.optString("annotEntry", "")).name
                val rawSrc = File(pagesDir, rawName).takeIf { rawName.isNotBlank() && it.isFile }
                val procSrc = File(pagesDir, procName).takeIf { procName.isNotBlank() && it.isFile }
                val annotSrc = File(pagesDir, annotName).takeIf { annotName.isNotBlank() && it.isFile }
                val source = rawSrc ?: procSrc ?: continue
                val tag = UUID.randomUUID().toString().take(8)
                val newRaw = File(scansDir, "restored_raw_${tag}.jpg")
                source.copyTo(newRaw, overwrite = true)
                createdFiles += newRaw
                val newProc = if (procSrc != null && procSrc != source) {
                    File(scansDir, "restored_proc_${tag}.jpg").also { procSrc.copyTo(it, overwrite = true); createdFiles += it }
                } else newRaw

                if (annotSrc != null) {
                    val dstAnnot = File("${newRaw.absolutePath}.annot.png")
                    annotSrc.copyTo(dstAnnot, overwrite = true)
                    createdFiles += dstAnnot
                }
                if (p.optBoolean("noStraighten", false)) {
                    QuadStore.markNoStraighten(newRaw.absolutePath)
                }

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

    companion object {
        private const val MANIFEST = "backup_manifest.json"
        private const val FORMAT_VERSION = 2
        private const val MAX_ENTRIES = 20_000
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB
        private val ENCRYPTED_MAGIC = "MSBAK1".toByteArray(Charsets.US_ASCII)
        private const val PBKDF2_ITERATIONS = 20_000
        private const val GCM_TAG_BITS = 128

        private fun deriveAesKey(password: String, salt: ByteArray): SecretKeySpec {
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            return try {
                val raw = runCatching {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                }.getOrElse {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
                }
                SecretKeySpec(raw, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        /** Writes "MSBAK1" + 16B salt + 12B IV to [out] and returns an AES-256-GCM CipherOutputStream. */
        internal fun wrapEncryptedOutputStream(out: OutputStream, password: String): OutputStream {
            val rng = SecureRandom()
            val salt = ByteArray(16).also { rng.nextBytes(it) }
            val iv = ByteArray(12).also { rng.nextBytes(it) }
            out.write(ENCRYPTED_MAGIC)
            out.write(salt)
            out.write(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            return CipherOutputStream(out, cipher)
        }

        /** Reads 16B salt + 12B IV from [input] (after "MSBAK1" magic) and returns an AES-256-GCM CipherInputStream. */
        internal fun unwrapEncryptedInputStream(input: InputStream, password: String): InputStream {
            val salt = ByteArray(16)
            val iv = ByteArray(12)
            if (input.readNBytesCompat(salt) != 16 || input.readNBytesCompat(iv) != 12) {
                throw IOException("Corrupt encrypted backup header")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            return CipherInputStream(input, cipher)
        }

        private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val r = read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            return total
        }
    }
}
