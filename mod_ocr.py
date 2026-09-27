import re

with open('app/src/main/java/com/example/engine/ocr/DocumentAiEngine.kt', 'r', encoding='utf-8') as f:
    content = f.read()

offline_ocr_replacement = """    fun performOfflineOcr(bitmap: Bitmap): DocumentAnalysisResult {
        return DocumentAnalysisResult(
            fullText = "Online OCR is currently unavailable.",
            suggestedTitle = "Document",
            detectedCategory = DocumentCategory.OTHER,
            fields = emptyList(),
            confidence = 0f,
            isAiPowered = false
        )
    }"""

content = re.sub(r'fun performOfflineOcr\(bitmap: Bitmap\): DocumentAnalysisResult \{.*?return DocumentAnalysisResult\([^)]*\)\n    \}', offline_ocr_replacement, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/engine/ocr/DocumentAiEngine.kt', 'w', encoding='utf-8') as f:
    f.write(content)
