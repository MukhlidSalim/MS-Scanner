package com.example.engine.ocr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * One recognized text line. Coordinates are normalized (0..1) to the image that was recognized, so they
 * stay valid for any scaled copy of that image (PDF page, thumbnail…).
 */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val isArabic: Boolean get() = text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFEFF' }
    val hasLatin: Boolean get() = text.any { it in 'A'..'Z' || it in 'a'..'z' }
}

/**
 * Line layout of a page, stored next to the image it was computed from ("<image>.ocr.json").
 * Used by PdfEngine to put an invisible, correctly positioned text layer on every page (real searchable
 * PDF), and by the analysis worker to know which pages still need OCR (no Room migration needed).
 * A page whose image changes gets a new path, so a stale layout can never be applied to it.
 */
object OcrLayoutStore {
    private fun fileFor(imagePath: String) = File("$imagePath.ocr.json")

    fun exists(imagePath: String): Boolean = imagePath.isNotBlank() && fileFor(imagePath).isFile

    fun save(imagePath: String, lines: List<OcrLine>) {
        if (imagePath.isBlank()) return
        try {
            val arr = JSONArray()
            lines.forEach { l ->
                arr.put(JSONObject().apply {
                    put("t", l.text); put("l", l.left.toDouble()); put("tp", l.top.toDouble())
                    put("r", l.right.toDouble()); put("b", l.bottom.toDouble())
                })
            }
            val tmp = File("$imagePath.ocr.json.tmp")
            tmp.writeText(JSONObject().put("v", 1).put("lines", arr).toString())
            if (!tmp.renameTo(fileFor(imagePath))) {
                fileFor(imagePath).writeText(tmp.readText()); tmp.delete()
            }
        } catch (_: Exception) { }
    }

    fun load(imagePath: String): List<OcrLine>? = try {
        val f = fileFor(imagePath)
        if (!f.isFile) null else {
            val arr = JSONObject(f.readText()).optJSONArray("lines") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                OcrLine(
                    o.optString("t"), o.optDouble("l").toFloat(), o.optDouble("tp").toFloat(),
                    o.optDouble("r").toFloat(), o.optDouble("b").toFloat()
                ).takeIf { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
            }
        }
    } catch (_: Exception) { null }

    /** Same geometry, new pixels (filter / brightness / annotation): the layout is still valid. */
    fun copy(fromImagePath: String, toImagePath: String) {
        if (fromImagePath == toImagePath || !exists(fromImagePath)) return
        runCatching { fileFor(fromImagePath).copyTo(fileFor(toImagePath), overwrite = true) }
    }

    fun delete(imagePath: String) {
        runCatching { fileFor(imagePath).delete() }
    }
}

/**
 * Provides the Arabic Tesseract model (tessdata_fast "ara", ~1.4 MB) in app-private storage.
 *  1. If the app ships it in assets/tessdata/ara.traineddata it is copied once (fully offline).
 *  2. Otherwise it is downloaded once over HTTPS from the official tesseract-ocr repository.
 * The file is written atomically and validated by the engine (a model Tesseract cannot load is deleted).
 */
object TessDataManager {
    const val ARABIC = "ara"
    private const val ASSET_PATH = "tessdata/ara.traineddata"
    private const val DOWNLOAD_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata"
    private const val MIN_BYTES = 1_000_000L
    private val mutex = Mutex()

    /** Directory passed to TessBaseAPI.init (it must CONTAIN the "tessdata" folder). */
    fun dataRoot(context: Context): File = File(context.applicationContext.filesDir, "ocr")

    private fun modelFile(context: Context) = File(dataRoot(context), "tessdata/ara.traineddata")

    fun isArabicReady(context: Context): Boolean = modelFile(context).length() >= MIN_BYTES

    /** Called when Tesseract cannot load the file: removed so the next call provisions a fresh copy. */
    fun invalidate(context: Context) {
        runCatching { modelFile(context).delete() }
    }

    /** Returns true when the Arabic model is available. Never throws. Safe to call from any thread. */
    suspend fun ensureArabic(context: Context, allowDownload: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = modelFile(context)
            if (target.length() >= MIN_BYTES) return@withLock true
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "ara.traineddata.part")
            try {
                // 1. Bundled asset (offline).
                try {
                    context.applicationContext.assets.open(ASSET_PATH).use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    }
                    if (tmp.length() >= MIN_BYTES && tmp.renameTo(target)) return@withLock true
                } catch (_: FileNotFoundException) {
                    // not bundled
                }
                if (!allowDownload) return@withLock false
                // 2. One-time download.
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withLock false
                    val body = resp.body ?: return@withLock false
                    body.byteStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                }
                tmp.length() >= MIN_BYTES && tmp.renameTo(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                tmp.delete()
            }
        }
    }
}
