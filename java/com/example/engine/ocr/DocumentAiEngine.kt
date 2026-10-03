package com.example.engine.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.data.model.DocumentCategory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class ExtractedField(
    val labelEn: String,
    val labelAr: String,
    val value: String
)

enum class OcrLanguage(val displayName: String) {
    AUTO("Auto-detect"),
    ARABIC("Arabic"),
    ENGLISH("English")
}

data class DocumentAnalysisResult(
    val fullText: String,
    val suggestedTitle: String,
    val detectedCategory: DocumentCategory,
    val fields: List<ExtractedField>,
    val confidence: Float,
    /** Positioned text lines (normalized coordinates) for the searchable PDF layer. */
    val lines: List<OcrLine> = emptyList(),
    /** "mlkit", "tesseract", "mlkit+tesseract" or "mlkit-fallback" (Arabic model not available). */
    val engine: String = ""
)

/**
 * Local (on-device) text recognition.
 *
 *  ENGLISH -> ML Kit Latin (fast).
 *  ARABIC  -> Tesseract "ara" (ML Kit has no on-device Arabic model).
 *  AUTO    -> both, merged line by line: Arabic lines from Tesseract + Latin lines from ML Kit that do not
 *             overlap them. Mixed Arabic/English documents (IDs, invoices, contracts) keep both scripts.
 * When the Arabic model is not available yet, ML Kit is used and the result is marked "mlkit-fallback"
 * so the caller can retry later instead of storing a wrong Arabic transcription for good.
 */
object DocumentAiEngine {

    private val tesseractLock = Mutex()

    private class Recognized(val text: String, val lines: List<OcrLine>, val confidence: Float)

    /** Kept for source compatibility (no Context -> ML Kit only). Prefer [analyze]. */
    suspend fun performOfflineOcr(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO,
        retryCount: Int = 0
    ): DocumentAnalysisResult = analyze(null, bitmap, language, retryCount)

    suspend fun analyze(
        context: Context?,
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO,
        retryCount: Int = 0
    ): DocumentAnalysisResult {
        return try {
            val arabicReady = context != null && TessDataManager.isArabicReady(context)
            val recognized: Recognized
            val engine: String
            when {
                language == OcrLanguage.ENGLISH -> {
                    recognized = runMlKit(bitmap); engine = "mlkit"
                }
                language == OcrLanguage.ARABIC && arabicReady -> {
                    recognized = runTesseract(context!!, bitmap) ?: runMlKit(bitmap)
                    engine = "tesseract"
                }
                language == OcrLanguage.AUTO && arabicReady -> {
                    val latin = runMlKit(bitmap)
                    val arabic = runTesseract(context!!, bitmap)
                    recognized = if (arabic == null) latin else merge(arabic, latin)
                    engine = if (arabic == null) "mlkit" else "mlkit+tesseract"
                }
                else -> {
                    recognized = runMlKit(bitmap); engine = "mlkit-fallback"
                }
            }
            buildResult(bitmap, recognized, engine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.google.mlkit.common.MlKitException) {
            if (retryCount < 2 && e.message?.contains("download", ignoreCase = true) == true) {
                delay(1000L * (retryCount + 1))
                analyze(context, bitmap, language, retryCount + 1)
            } else {
                handleOcrFailureSync(e)
            }
        } catch (e: Exception) {
            handleOcrFailureSync(e)
        }
    }

    // ------------------------------------------------------------------ engines

    private suspend fun runMlKit(bitmap: Bitmap): Recognized = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        continuation.invokeOnCancellation { recognizer.close() }
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val t = line.text.trim()
                    if (t.isEmpty()) null
                    else OcrLine(t, box.left / w, box.top / h, box.right / w, box.bottom / h)
                }
                recognizer.close()
                if (continuation.isActive) continuation.resume(Recognized(text.text, lines, if (text.text.isBlank()) 0.25f else 0.8f))
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    /** Tesseract is single-instance (memory heavy); calls are serialized. Returns null if it cannot run. */
    private suspend fun runTesseract(context: Context, bitmap: Bitmap): Recognized? = tesseractLock.withLock {
        withContext(Dispatchers.Default) {
            val api = TessBaseAPI()
            try {
                val ok = try {
                    api.init(TessDataManager.dataRoot(context).absolutePath, TessDataManager.ARABIC, TessBaseAPI.OEM_LSTM_ONLY)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    TessDataManager.invalidate(context) // corrupt / incompatible model: re-provisioned next time
                    return@withContext null
                }
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setImage(bitmap)
                val full = api.getUTF8Text() ?: ""
                val w = bitmap.width.toFloat().coerceAtLeast(1f)
                val h = bitmap.height.toFloat().coerceAtLeast(1f)
                val lines = ArrayList<OcrLine>()
                val iterator = api.getResultIterator()
                if (iterator != null) {
                    try {
                        iterator.begin()
                        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                        do {
                            val t = iterator.getUTF8Text(level)?.trim().orEmpty()
                            val r = iterator.getBoundingRect(level)
                            val conf = iterator.confidence(level)
                            if (t.isNotEmpty() && r != null && conf >= 35f) {
                                lines += OcrLine(t, r.left / w, r.top / h, r.right / w, r.bottom / h)
                            }
                        } while (iterator.next(level))
                    } finally {
                        iterator.delete()
                    }
                }
                val text = if (lines.isNotEmpty()) lines.joinToString("\n") { it.text } else full.trim()
                Recognized(text, lines, (api.meanConfidence() / 100f).coerceIn(0f, 1f))
            } finally {
                api.recycle()
            }
        }
    }

    /** Arabic lines from Tesseract + Latin-only ML Kit lines that do not overlap them, in reading order. */
    private fun merge(arabic: Recognized, latin: Recognized): Recognized {
        val arabicLines = arabic.lines.filter { it.isArabic }
        if (arabicLines.isEmpty()) return latin
        val extraLatin = latin.lines.filter { l ->
            l.hasLatin && !l.isArabic && arabicLines.none { a -> overlapRatio(a, l) > 0.4f }
        }
        val merged = (arabicLines + extraLatin).sortedWith(compareBy<OcrLine> { ((it.top + it.bottom) / 2f * 40).toInt() }.thenBy { it.left })
        return Recognized(merged.joinToString("\n") { it.text }, merged, max(arabic.confidence, latin.confidence))
    }

    private fun overlapRatio(a: OcrLine, b: OcrLine): Float {
        val ix = min(a.right, b.right) - max(a.left, b.left)
        val iy = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (ix <= 0f || iy <= 0f) return 0f
        val smaller = min((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top)).coerceAtLeast(1e-6f)
        return (ix * iy) / smaller
    }

    // ------------------------------------------------------------------ classification & title

    private val categoryRules: List<Pair<DocumentCategory, List<Regex>>> = listOf(
        DocumentCategory.INVOICE to listOf(
            Regex("فاتور[هة]"), Regex("ضريب[هة]"), Regex("\\b(tax\\s+)?invoice\\b", RegexOption.IGNORE_CASE), Regex("\\bVAT\\b")
        ),
        DocumentCategory.RECEIPT to listOf(
            Regex("[إا]يصال"), Regex("سند\\s+قبض"), Regex("\\breceipt\\b", RegexOption.IGNORE_CASE)
        ),
        DocumentCategory.ID_CARD to listOf(
            Regex("بطاق[هة]\\s+شخصي[هة]"), Regex("الرقم\\s+المدني"), Regex("جواز\\s+سفر"), Regex("هوي[هة]"),
            Regex("\\b(identity|resident)\\s+card\\b", RegexOption.IGNORE_CASE), Regex("\\bcivil\\s+(no|number)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpassport\\b", RegexOption.IGNORE_CASE), Regex("P<[A-Z]{3}")
        ),
        DocumentCategory.CONTRACT to listOf(
            Regex("عقد"), Regex("اتفاقي[هة]"), Regex("\\b(contract|agreement)\\b", RegexOption.IGNORE_CASE)
        )
    )

    private fun classify(text: String): DocumentCategory {
        if (text.isBlank()) return DocumentCategory.OTHER
        return categoryRules
            .map { (cat, rules) -> cat to rules.sumOf { r -> r.findAll(text).count() } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: DocumentCategory.OTHER
    }

    private fun buildResult(bitmap: Bitmap, recognized: Recognized, engine: String): DocumentAnalysisResult {
        val fullText = recognized.text.trim()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        // Passport / ID card: the MRZ (check-digit validated) is the most reliable source.
        val mrz = MrzParser.parse(fullText)
        val category = if (mrz != null) DocumentCategory.ID_CARD else classify(fullText)
        val arabicDominant = fullText.count { it in '\u0600'..'\u06FF' } > fullText.count { it in 'A'..'Z' || it in 'a'..'z' }
        // First informative line (letters, 3..40 chars) gives a meaningful title instead of "Document - date".
        val headline = fullText.lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> line.length in 3..60 && line.count { it.isLetter() } >= 3 }
            ?.take(40)
        val label = if (arabicDominant) category.displayNameAr else category.displayNameEn
        val suggestedTitle = when {
            mrz != null && mrz.fullName.isNotBlank() -> {
                val kind = when {
                    mrz.isPassport -> if (arabicDominant) "جواز سفر" else "Passport"
                    else -> if (arabicDominant) "بطاقة هوية" else "ID Card"
                }
                "$kind - ${mrz.fullName}"
            }
            headline != null && category != DocumentCategory.OTHER -> "$label - $headline"
            headline != null -> headline
            else -> "Document - $dateStr"
        }
        return DocumentAnalysisResult(
            fullText = fullText,
            suggestedTitle = suggestedTitle,
            detectedCategory = category,
            fields = mrzFields(mrz) + listOf(
                ExtractedField("Date Scanned", "تاريخ المسح", dateStr),
                ExtractedField("Resolution", "الدقة", "${bitmap.width}x${bitmap.height} px"),
                ExtractedField("OCR Engine", "محرك التعرف", engine)
            ),
            confidence = if (fullText.isBlank()) 0.25f else recognized.confidence.coerceAtLeast(0.3f),
            lines = recognized.lines,
            engine = engine
        )
    }

    /** Only fields whose check digit passed are shown as values; others are marked unverified. */
    private fun mrzFields(mrz: MrzData?): List<ExtractedField> {
        if (mrz == null) return emptyList()
        fun v(value: String, ok: Boolean) = if (ok) value else "$value (?)"
        return listOfNotNull(
            ExtractedField("Name", "الاسم", mrz.fullName).takeIf { mrz.fullName.isNotBlank() },
            ExtractedField("Document No.", "رقم الوثيقة", v(mrz.documentNumber, mrz.documentNumberValid)),
            ExtractedField("Nationality", "الجنسية", mrz.nationality).takeIf { mrz.nationality.isNotBlank() },
            ExtractedField("Date of Birth", "تاريخ الميلاد", v(MrzParser.formatDate(mrz.birthDate, true), mrz.birthDateValid)),
            ExtractedField("Expiry Date", "تاريخ الانتهاء", v(MrzParser.formatDate(mrz.expiryDate, false), mrz.expiryDateValid)),
            ExtractedField("Sex", "الجنس", mrz.sex).takeIf { mrz.sex.isNotBlank() },
            ExtractedField("Issuing Country", "دولة الإصدار", mrz.issuingCountry)
        )
    }

    private fun handleOcrFailureSync(e: Exception): DocumentAnalysisResult {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val message = "OCR Failed: ${e.message ?: e.javaClass.simpleName}"
        return DocumentAnalysisResult(
            fullText = message,
            suggestedTitle = "Document - $dateStr",
            detectedCategory = DocumentCategory.OTHER,
            fields = emptyList(),
            confidence = 0f
        )
    }
}
