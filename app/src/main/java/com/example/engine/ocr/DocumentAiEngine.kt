package com.example.engine.ocr

import android.graphics.Bitmap
import com.example.data.model.DocumentCategory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val confidence: Float
)

/**
 * Local document text extraction. No network or remote analysis is used.
 */
object DocumentAiEngine {

    suspend fun performOfflineOcr(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO,
        retryCount: Int = 0
    ): DocumentAnalysisResult {
        return try {
            performOfflineOcrInternal(bitmap)
        } catch (e: com.google.mlkit.common.MlKitException) {
            if (retryCount < 2 && e.message?.contains("download", ignoreCase = true) == true) {
                delay(1000L * (retryCount + 1))
                performOfflineOcr(bitmap, language, retryCount + 1)
            } else {
                handleOcrFailureSync(e)
            }
        } catch (e: Exception) {
            handleOcrFailureSync(e)
        }
    }

    private suspend fun performOfflineOcrInternal(
        bitmap: Bitmap
    ): DocumentAnalysisResult = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        continuation.invokeOnCancellation { recognizer.close() }

        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (!continuation.isActive) {
                    recognizer.close()
                    return@addOnSuccessListener
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val fullText = text.text
                val fields = listOf(
                    ExtractedField("Date Scanned", "تاريخ المسح", dateStr),
                    ExtractedField("Resolution", "الدقة", "${bitmap.width}x${bitmap.height} px")
                )

                val category = when {
                    fullText.contains("فاتورة") || fullText.contains("invoice", ignoreCase = true) -> DocumentCategory.INVOICE
                    fullText.contains("هوية") || fullText.contains("ID", ignoreCase = true) -> DocumentCategory.ID_CARD
                    fullText.contains("عقد") || fullText.contains("contract", ignoreCase = true) -> DocumentCategory.CONTRACT
                    fullText.contains("receipt", ignoreCase = true) || fullText.contains("إيصال") -> DocumentCategory.RECEIPT
                    else -> DocumentCategory.OTHER
                }

                val suggestedTitle = when (category) {
                    DocumentCategory.INVOICE -> "Invoice - $dateStr"
                    DocumentCategory.ID_CARD -> "ID Card - $dateStr"
                    DocumentCategory.CONTRACT -> "Contract - $dateStr"
                    DocumentCategory.RECEIPT -> "Receipt - $dateStr"
                    else -> "Document - $dateStr"
                }

                recognizer.close()
                continuation.resume(
                    DocumentAnalysisResult(
                        fullText = fullText,
                        suggestedTitle = suggestedTitle,
                        detectedCategory = category,
                        fields = fields,
                        confidence = if (fullText.isBlank()) 0.25f else 0.80f
                    )
                )
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
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
