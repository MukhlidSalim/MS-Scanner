import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Add mergeToPdf method
pdf_method = """
    suspend fun mergeToPdf(context: Context, images: List<Bitmap>): File = withContext(Dispatchers.IO) {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val outputFile = File(context.cacheDir, "shared_document_${System.currentTimeMillis()}.pdf")
        
        try {
            for ((index, bitmap) in images.withIndex()) {
                // A4 size in points (1/72 inch): 595 x 842
                // We'll use the bitmap's aspect ratio to fit it within A4 size
                val pageWidth = 595
                val pageHeight = 842
                
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                
                val canvas = page.canvas
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                
                // Calculate scaling to maintain aspect ratio
                val scale = minOf(
                    pageWidth.toFloat() / bitmap.width,
                    pageHeight.toFloat() / bitmap.height
                )
                
                val scaledWidth = bitmap.width * scale
                val scaledHeight = bitmap.height * scale
                
                // Center the image on the page
                val left = (pageWidth - scaledWidth) / 2f
                val top = (pageHeight - scaledHeight) / 2f
                
                val destRect = android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)
                
                pdfDocument.finishPage(page)
            }
            
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
        } finally {
            pdfDocument.close()
        }
        
        outputFile
    }
"""

content = content.replace('object ImageProcessor {', 'object ImageProcessor {\n' + pdf_method)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
