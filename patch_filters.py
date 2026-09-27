import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace applyMagicColor
old_magic = """    private fun applyMagicColor(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = 1.40f
        val brightness = 12f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.25f)
        colorMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }"""

new_magic = """    private fun applyMagicColor(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        
        // Adaptive Background Flattening (True Document Scanner Shadow Removal)
        val sampleSize = 48
        val scaleW = sampleSize
        val scaleH = (sampleSize * (height.toFloat() / width)).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val bgMap = Bitmap.createScaledBitmap(tiny, width, height, true)
        tiny.recycle()
        
        val srcPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        bgMap.getPixels(bgPixels, 0, width, 0, 0, width, height)
        bgMap.recycle()
        
        val outPixels = IntArray(width * height)
        
        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val sr = (p shr 16) and 0xFF
            val sg = (p shr 8) and 0xFF
            val sb = p and 0xFF
            
            val bp = bgPixels[i]
            val br = (bp shr 16) and 0xFF
            val bg = (bp shr 8) and 0xFF
            val bb = bp and 0xFF
            
            // Normalize illumination by dividing by background map
            var or = (sr * 255) / br.coerceAtLeast(1)
            var og = (sg * 255) / bg.coerceAtLeast(1)
            var ob = (sb * 255) / bb.coerceAtLeast(1)
            
            // Boost saturation slightly and increase contrast
            val lum = 0.299f * or + 0.587f * og + 0.114f * ob
            val sat = 1.25f
            or = (lum + (or - lum) * sat).toInt()
            og = (lum + (og - lum) * sat).toInt()
            ob = (lum + (ob - lum) * sat).toInt()
            
            val contrast = 1.35f
            or = (((or / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()
            og = (((og / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()
            ob = (((ob / 255f - 0.5f) * contrast + 0.5f) * 255).toInt()
            
            outPixels[i] = (0xFF shl 24) or (or.coerceIn(0, 255) shl 16) or (og.coerceIn(0, 255) shl 8) or ob.coerceIn(0, 255)
        }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }"""

content = content.replace(old_magic, new_magic)

# Replace applyHighContrastBW to use adaptive thresholding as well
old_bw = """    private fun applyHighContrastBW(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 2.0f
            val brightness = 20f
            val m = floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
            postConcat(ColorMatrix(m))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }"""

new_bw = """    private fun applyHighContrastBW(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        
        // Adaptive B&W Flattening
        val sampleSize = 48
        val scaleW = sampleSize
        val scaleH = (sampleSize * (height.toFloat() / width)).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, scaleW, scaleH, true)
        val bgMap = Bitmap.createScaledBitmap(tiny, width, height, true)
        tiny.recycle()
        
        val srcPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        bgMap.getPixels(bgPixels, 0, width, 0, 0, width, height)
        bgMap.recycle()
        
        val outPixels = IntArray(width * height)
        
        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val sr = (p shr 16) and 0xFF
            val sg = (p shr 8) and 0xFF
            val sb = p and 0xFF
            val sl = 0.299f * sr + 0.587f * sg + 0.114f * sb
            
            val bp = bgPixels[i]
            val br = (bp shr 16) and 0xFF
            val bg = (bp shr 8) and 0xFF
            val bb = bp and 0xFF
            val bl = 0.299f * br + 0.587f * bg + 0.114f * bb
            
            // Division normalizes the illumination
            var lum = (sl * 255) / bl.coerceAtLeast(1f)
            
            // High contrast binarization-like effect
            val contrast = 2.5f
            lum = ((lum / 255f - 0.5f) * contrast + 0.5f) * 255
            
            val finalLum = lum.toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (finalLum shl 16) or (finalLum shl 8) or finalLum
        }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }"""

content = content.replace(old_bw, new_bw)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
