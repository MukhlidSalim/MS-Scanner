import re
with open("app/src/main/java/com/example/engine/cv/ImageProcessor.kt", "r", encoding="utf-8") as f:
    content = f.read()

merge_func = """
    fun createIdCardCollage(context: Context, frontPath: String, backPath: String, outPrefix: String): String {
        try {
            val frontStream = context.contentResolver.openInputStream(android.net.Uri.fromFile(java.io.File(frontPath)))
            val backStream = context.contentResolver.openInputStream(android.net.Uri.fromFile(java.io.File(backPath)))
            val frontBmp = BitmapFactory.decodeStream(frontStream)
            val backBmp = BitmapFactory.decodeStream(backStream)
            frontStream?.close()
            backStream?.close()
            
            if (frontBmp == null || backBmp == null) return frontPath

            // A4 ratio at 150 DPI approx -> 1240 x 1754
            val canvasW = 1240
            val canvasH = 1754
            val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(result)
            canvas.drawColor(android.graphics.Color.WHITE)
            
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            
            // Draw front on top half, centered
            val frontRatio = frontBmp.width.toFloat() / frontBmp.height.toFloat()
            val targetW = canvasW * 0.8f
            val targetH = targetW / frontRatio
            
            val frontLeft = (canvasW - targetW) / 2f
            val frontTop = canvasH * 0.15f
            val frontRect = android.graphics.RectF(frontLeft, frontTop, frontLeft + targetW, frontTop + targetH)
            canvas.drawBitmap(frontBmp, null, frontRect, paint)
            
            // Draw back on bottom half, centered
            val backRatio = backBmp.width.toFloat() / backBmp.height.toFloat()
            val targetWBack = canvasW * 0.8f
            val targetHBack = targetWBack / backRatio
            
            val backLeft = (canvasW - targetWBack) / 2f
            val backTop = canvasH * 0.55f
            val backRect = android.graphics.RectF(backLeft, backTop, backLeft + targetWBack, backTop + targetHBack)
            canvas.drawBitmap(backBmp, null, backRect, paint)
            
            frontBmp.recycle()
            backBmp.recycle()
            
            val outPath = saveBitmapToFile(context, result, outPrefix)
            result.recycle()
            return outPath
        } catch (e: Exception) {
            e.printStackTrace()
            return frontPath
        }
    }
"""
if "fun createIdCardCollage" not in content:
    content = content.replace("object ImageProcessor {", "object ImageProcessor {" + merge_func)
    with open("app/src/main/java/com/example/engine/cv/ImageProcessor.kt", "w", encoding="utf-8") as f:
        f.write(content)
