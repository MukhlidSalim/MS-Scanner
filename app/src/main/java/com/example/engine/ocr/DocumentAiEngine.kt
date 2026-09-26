package com.example.engine.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.DocumentCategory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.suspendCoroutine

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
     * Offline OCR text extractor using ML Kit Text Recognition
     */
    suspend fun performOfflineOcr(bitmap: Bitmap): DocumentAnalysisResult = suspendCoroutine { continuation ->
        val recognizer = TextRecognition.getClient()
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val fields = mutableListOf<ExtractedField>()

                // Heuristic extraction
                fields.add(ExtractedField("Date Scanned", "تاريخ المسح", dateStr))
                fields.add(ExtractedField("Resolution", "الدقة", "${bitmap.width}x${bitmap.height} px"))

                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val category = when {
                    aspect in 1.4f..1.8f || aspect in 0.55f..0.7f -> DocumentCategory.ID_CARD
                    aspect < 0.45f -> DocumentCategory.RECEIPT
                    else -> DocumentCategory.OTHER
                }

                val suggestedTitle = when (category) {
                    DocumentCategory.ID_CARD -> "ID Card - $dateStr"
                    DocumentCategory.RECEIPT -> "Receipt - $dateStr"
                    else -> "Document - $dateStr"
                }

                continuation.resume(
                    DocumentAnalysisResult(
                        fullText = text.text,
                        suggestedTitle = suggestedTitle,
                        detectedCategory = category,
                        fields = fields,
                        confidence = 0.8f,
                        isAiPowered = false
                    )
                )
            }
            .addOnFailureListener {
                // Fallback to simple placeholder if ML Kit fails
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                continuation.resume(
                    DocumentAnalysisResult(
                        fullText = "OCR Failed: ${it.message}",
                        suggestedTitle = "Document - $dateStr",
                        detectedCategory = DocumentCategory.OTHER,
                        fields = emptyList(),
                        confidence = 0f,
                        isAiPowered = false
                    )
                )
            }
    }

    /**
     * Deep AI Document Intelligence using Gemini 2.5 Flash for complete Arabic + English OCR,
     * table extraction, metadata, and category classification.
     */
    suspend fun analyzeWithGemini(bitmap: Bitmap, language: OcrLanguage = OcrLanguage.AUTO): DocumentAnalysisResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext performOfflineOcr(bitmap)
        }
        
        val languagePrompt = when (language) {
            OcrLanguage.AUTO -> "Detect the language automatically (could be Arabic, English, or mixed)."
            OcrLanguage.ARABIC -> "The document is primarily in Arabic."
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
                    {"labelEn": "Date", "labelAr": "التاريخ", "value": "..."},
                    {"labelEn": "Total Amount", "labelAr": "المبلغ الإجمالي", "value": "..."},
                    {"labelEn": "Document Number", "labelAr": "رقم المستند", "value": "..."}
                  ],
                  "confidence": 0.95
                }
                Provide only the valid JSON response without markdown tags.
            """.trimIndent()

            val requestBodyJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
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
            if (!response.isSuccessful) {
                return@withContext performOfflineOcr(bitmap)
            }

            val responseBody = response.body?.string() ?: return@withContext performOfflineOcr(bitmap)
            val jsonRoot = JSONObject(responseBody)
            val candidates = jsonRoot.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val textPart = parts?.optJSONObject(0)?.optString("text") ?: ""

            val cleanJson = textPart.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsedObj = JSONObject(cleanJson)

            val fullText = parsedObj.optString("fullText", "")
            val suggestedTitle = parsedObj.optString("suggestedTitle", "Document ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}")
            val categoryStr = parsedObj.optString("category", "OTHER")
            val category = try {
                DocumentCategory.valueOf(categoryStr)
            } catch (e: Exception) {
                DocumentCategory.OTHER
            }

            val fieldsList = mutableListOf<ExtractedField>()
            val fieldsArray = parsedObj.optJSONArray("fields")
            if (fieldsArray != null) {
                for (i in 0 until fieldsArray.length()) {
                    val fieldObj = fieldsArray.optJSONObject(i) ?: continue
                    val labelEn = fieldObj.optString("labelEn", "Field")
                    val labelAr = fieldObj.optString("labelAr", "حقل")
                    val value = fieldObj.optString("value", "")
                    if (value.isNotBlank()) {
                        fieldsList.add(ExtractedField(labelEn, labelAr, value))
                    }
                }
            }

            DocumentAnalysisResult(
                fullText = fullText.ifBlank { "Text extracted from document" },
                suggestedTitle = suggestedTitle,
                detectedCategory = category,
                fields = fieldsList,
                confidence = parsedObj.optDouble("confidence", 0.95).toFloat(),
                isAiPowered = true
            )
        } catch (e: Exception) {
            performOfflineOcr(bitmap)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1200
        val scale = minOf(1.0f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
