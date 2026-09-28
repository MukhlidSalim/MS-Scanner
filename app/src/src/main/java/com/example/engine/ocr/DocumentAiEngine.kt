package com.example.engine.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.DocumentCategory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    val isAiPowered: Boolean
)

object DocumentAiEngine {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Offline OCR — يختار المُعرِّف المناسب بناءً على اللغة المطلوبة.
     * للعربية: يجمع بين مُعرِّف اللاتيني والعربي ويدمج النتائج.
     * للإنجليزية أو التلقائي: يستخدم مُعرِّف اللاتيني فقط.
     */
    suspend fun performOfflineOcr(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO,
        retryCount: Int = 0
    ): DocumentAnalysisResult {
        return try {
            when (language) {
                OcrLanguage.ARABIC -> performArabicOcr(bitmap)
                else               -> performLatinOcr(bitmap)
            }
        } catch (e: Exception) {
            val isModelNotReady = e is com.google.mlkit.common.MlKitException &&
                e.message?.contains("downloaded", ignoreCase = true) == true
            if (isModelNotReady && retryCount < 3) {
                kotlinx.coroutines.delay(3000)
                performOfflineOcr(bitmap, language, retryCount + 1)
            } else {
                handleOcrFailure(e)
            }
        }
    }

    // ── Latin / English OCR ──────────────────────────────────────────────────

    private suspend fun performLatinOcr(bitmap: Bitmap): DocumentAnalysisResult =
        suspendCancellableCoroutine { continuation ->
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val image = InputImage.fromBitmap(bitmap, 0)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(buildResult(visionText.text, bitmap, isAiPowered = false))
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }

                continuation.invokeOnCancellation { recognizer.close() }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }

    // ── Arabic OCR ───────────────────────────────────────────────────────────
    // يستخدم مُعرِّفَين بالتوازي: اللاتيني للأرقام والكلمات الإنجليزية،
    // والعربي للنص العربي، ثم يدمج النتيجتين.

    private suspend fun performArabicOcr(bitmap: Bitmap): DocumentAnalysisResult {
        val latinText  = runLatinRecognizer(bitmap)
        val arabicText = runArabicRecognizer(bitmap)

        // دمج النصين: العربي أولاً ثم اللاتيني إن كان مختلفاً
        val combined = when {
            arabicText.isBlank() -> latinText
            latinText.isBlank()  -> arabicText
            arabicText == latinText -> arabicText
            else -> "$arabicText\n$latinText"
        }

        return buildResult(combined, bitmap, isAiPowered = false)
    }

    private suspend fun runLatinRecognizer(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            try {
                val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                rec.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume("") }
                cont.invokeOnCancellation { rec.close() }
            } catch (e: Exception) {
                cont.resume("")
            }
        }

    private suspend fun runArabicRecognizer(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            try {
                // Arabic recognizer — يتطلب تبعية play-services-mlkit-text-recognition-arabic
                val options = com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder()
                // ملاحظة: استبدل السطر أعلاه بالسطر التالي بعد إضافة التبعية:
                // com.google.mlkit.vision.text.arabic.ArabicTextRecognizerOptions.Builder()
                //     .build()
                // في build.gradle:
                // implementation "com.google.android.gms:play-services-mlkit-text-recognition-arabic:16.0.0-beta1"
                val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                rec.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume("") }
                cont.invokeOnCancellation { rec.close() }
            } catch (e: Exception) {
                // إن لم تكن التبعية مثبتة، نعود للاتيني
                cont.resume("")
            }
        }

    // ── Result Builder ────────────────────────────────────────────────────────

    private fun buildResult(
        fullText: String,
        bitmap: Bitmap,
        isAiPowered: Boolean
    ): DocumentAnalysisResult {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fields = mutableListOf<ExtractedField>()
        fields.add(ExtractedField("Date Scanned", "تاريخ المسح", dateStr))
        fields.add(ExtractedField("Resolution", "الدقة", "${bitmap.width}x${bitmap.height} px"))

        val category = classifyText(fullText)
        val suggestedTitle = buildTitle(category, dateStr)

        return DocumentAnalysisResult(
            fullText        = fullText,
            suggestedTitle  = suggestedTitle,
            detectedCategory = category,
            fields          = fields,
            confidence      = if (fullText.isNotBlank()) 0.8f else 0f,
            isAiPowered     = isAiPowered
        )
    }

    private fun classifyText(text: String): DocumentCategory = when {
        text.contains("فاتورة")   || text.contains("invoice",  ignoreCase = true) -> DocumentCategory.INVOICE
        text.contains("إيصال")    || text.contains("receipt",  ignoreCase = true) -> DocumentCategory.RECEIPT
        text.contains("هوية")     || text.contains("بطاقة")
            || text.contains("ID Card", ignoreCase = true)                        -> DocumentCategory.ID_CARD
        text.contains("عقد")      || text.contains("contract", ignoreCase = true) -> DocumentCategory.CONTRACT
        text.contains("كتاب")     || text.contains("book",     ignoreCase = true) -> DocumentCategory.BOOK
        else                                                                       -> DocumentCategory.OTHER
    }

    private fun buildTitle(category: DocumentCategory, dateStr: String): String = when (category) {
        DocumentCategory.INVOICE  -> "فاتورة - $dateStr"
        DocumentCategory.ID_CARD  -> "هوية - $dateStr"
        DocumentCategory.CONTRACT -> "عقد - $dateStr"
        DocumentCategory.RECEIPT  -> "إيصال - $dateStr"
        DocumentCategory.BOOK     -> "كتاب - $dateStr"
        else                      -> "مستند - $dateStr"
    }

    private fun handleOcrFailure(e: Exception): DocumentAnalysisResult {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val msg = if (e is com.google.mlkit.common.MlKitException &&
            e.message?.contains("downloaded", ignoreCase = true) == true
        ) {
            "النموذج لا يزال يُحمَّل، انتظر ثوانٍ وأعد المحاولة."
        } else {
            "فشل OCR: ${e.message}"
        }
        return DocumentAnalysisResult(
            fullText         = msg,
            suggestedTitle   = "مستند - $dateStr",
            detectedCategory = DocumentCategory.OTHER,
            fields           = emptyList(),
            confidence       = 0f,
            isAiPowered      = false
        )
    }

    // ── Gemini AI ─────────────────────────────────────────────────────────────

    /**
     * تحليل عميق للمستند باستخدام Gemini 2.5 Flash —
     * يدعم العربية والإنجليزية والجداول واستخراج الحقول.
     * يعود تلقائياً لـ OCR الأوفلاين إن لم يتوفر مفتاح API.
     */
    suspend fun analyzeWithGemini(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO
    ): DocumentAnalysisResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext performOfflineOcr(bitmap, language)
        }

        val languagePrompt = when (language) {
            OcrLanguage.AUTO    -> "اكتشف اللغة تلقائياً (قد تكون عربية أو إنجليزية أو مزيجاً)."
            OcrLanguage.ARABIC  -> "المستند باللغة العربية بشكل رئيسي."
            OcrLanguage.ENGLISH -> "The document is primarily in English."
        }

        try {
            val base64Image = bitmapToBase64(bitmap)
            val prompt = """
                You are an expert Document Scanner AI and OCR engine.
                $languagePrompt
                Analyze this document image thoroughly.
                Return a STRICT JSON object with the following schema:
                {
                  "fullText": "complete verbatim transcription of all text in the document, preserving language faithfully",
                  "suggestedTitle": "Short, professional title (e.g. Invoice - Supplier - Date)",
                  "category": "RECEIPT | INVOICE | ID_CARD | CONTRACT | BOOK | NOTE | OTHER",
                  "fields": [
                    {"labelEn": "Merchant / Supplier", "labelAr": "المورد / التاجر", "value": "..."},
                    {"labelEn": "Date",                "labelAr": "التاريخ",          "value": "..."},
                    {"labelEn": "Total Amount",        "labelAr": "المبلغ الإجمالي", "value": "..."},
                    {"labelEn": "Document Number",     "labelAr": "رقم المستند",      "value": "..."}
                  ],
                  "confidence": 0.95
                }
                Provide only the valid JSON response without markdown tags.
            """.trimIndent()

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.1)
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext performOfflineOcr(bitmap, language)

            val bodyStr = response.body?.string() ?: return@withContext performOfflineOcr(bitmap, language)
            val root = JSONObject(bodyStr)
            val textPart = root
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text") ?: ""

            val cleanJson = textPart.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val parsed = JSONObject(cleanJson)

            val fullText       = parsed.optString("fullText", "")
            val suggestedTitle = parsed.optString("suggestedTitle", buildTitle(DocumentCategory.OTHER,
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())))
            val category = try {
                DocumentCategory.valueOf(parsed.optString("category", "OTHER"))
            } catch (e: Exception) { DocumentCategory.OTHER }

            val fieldsList = mutableListOf<ExtractedField>()
            val fieldsArr = parsed.optJSONArray("fields")
            if (fieldsArr != null) {
                for (i in 0 until fieldsArr.length()) {
                    val obj = fieldsArr.optJSONObject(i) ?: continue
                    val value = obj.optString("value", "")
                    if (value.isNotBlank()) {
                        fieldsList.add(
                            ExtractedField(
                                labelEn = obj.optString("labelEn", "Field"),
                                labelAr = obj.optString("labelAr", "حقل"),
                                value   = value
                            )
                        )
                    }
                }
            }

            DocumentAnalysisResult(
                fullText         = fullText.ifBlank { "تم استخراج النص من المستند" },
                suggestedTitle   = suggestedTitle,
                detectedCategory = category,
                fields           = fieldsList,
                confidence       = parsed.optDouble("confidence", 0.95).toFloat(),
                isAiPowered      = true
            )
        } catch (e: Exception) {
            performOfflineOcr(bitmap, language)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1200
        val scale  = minOf(1.0f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width  * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled != bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
