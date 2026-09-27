import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_merge = """    suspend fun mergeToPdf(context: Context, bitmaps: List<Bitmap>): File = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "shared_document_${System.currentTimeMillis()}.pdf")
        val pdfDocument = android.graphics.pdf.PdfDocument()
        
        try {
            // Standard A4 size at 72 PPI (595 x 842 points)
            val pageWidth = 595
            val pageHeight = 842
            
            for ((index, bitmap) in bitmaps.withIndex()) {
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
            }"""

new_merge = """    suspend fun mergeToPdf(context: Context, imagePaths: List<String>): File = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "shared_document_${System.currentTimeMillis()}.pdf")
        val pdfDocument = android.graphics.pdf.PdfDocument()
        
        try {
            // Standard A4 size at 72 PPI (595 x 842 points)
            val pageWidth = 595
            val pageHeight = 842
            
            for ((index, path) in imagePaths.withIndex()) {
                val bitmap = loadBitmapFromFile(path) ?: continue
                
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
                bitmap.recycle() // CRITICAL: Free memory for each page immediately
            }"""

content = content.replace(old_merge, new_merge)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
