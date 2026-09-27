import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

import re

# We will replace the entire primaryMlKitDetection and detectDocumentEdgesOpenCV methods.
start_idx = content.find('private fun primaryMlKitDetection(bitmap: Bitmap): DocumentQuad {')
end_idx = content.find('fun smoothQuad(', start_idx)

if start_idx != -1 and end_idx != -1:
    old_methods = content[start_idx:end_idx]
    
    new_methods = """private fun primaryMlKitDetection(bitmap: Bitmap): DocumentQuad {
        return detectEdgesWithParams(bitmap, 0.25f, 0.03f)
    }

    private fun detectDocumentEdgesOpenCV(bitmap: Bitmap): DocumentQuad {
        // Fallback with higher threshold and larger margin to ignore clutter
        return detectEdgesWithParams(bitmap, 0.40f, 0.10f)
    }

    private fun detectEdgesWithParams(bitmap: Bitmap, thresholdFactor: Float, marginPct: Float): DocumentQuad {
        try {
            // Scale down to a fixed reasonable size for processing speed (300-400px)
            val maxDim = 400f
            val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
            val sampleW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val sampleH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
            val pixels = IntArray(sampleW * sampleH)
            sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            sample.recycle()

            val lum = FloatArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            // Sobel Gradient
            val grad = FloatArray(pixels.size)
            var maxGrad = 0f
            var sumGrad = 0f
            
            for (y in 1 until sampleH - 1) {
                val row = y * sampleW
                val prevRow = (y - 1) * sampleW
                val nextRow = (y + 1) * sampleW
                
                for (x in 1 until sampleW - 1) {
                    val idx = row + x
                    val gx = (lum[prevRow + x + 1] + 2f * lum[row + x + 1] + lum[nextRow + x + 1]) -
                             (lum[prevRow + x - 1] + 2f * lum[row + x - 1] + lum[nextRow + x - 1])
                    val gy = (lum[nextRow + x - 1] + 2f * lum[nextRow + x] + lum[nextRow + x + 1]) -
                             (lum[prevRow + x - 1] + 2f * lum[prevRow + x] + lum[prevRow + x + 1])
                    val g = kotlin.math.sqrt(gx * gx + gy * gy)
                    grad[idx] = g
                    sumGrad += g
                    if (g > maxGrad) maxGrad = g
                }
            }
            
            val avgGrad = sumGrad / pixels.size
            val threshold = avgGrad + (maxGrad - avgGrad) * thresholdFactor
            
            // Extract the 4 extreme corners using x+y and x-y optimization
            var minSum = Float.MAX_VALUE // Top Left
            var maxSum = Float.MIN_VALUE // Bottom Right
            var minDiff = Float.MAX_VALUE // Bottom Left
            var maxDiff = Float.MIN_VALUE // Top Right
            
            var tl = android.graphics.PointF(0.1f, 0.1f)
            var tr = android.graphics.PointF(0.9f, 0.1f)
            var bl = android.graphics.PointF(0.1f, 0.9f)
            var br = android.graphics.PointF(0.9f, 0.9f)
            
            val marginX = sampleW * marginPct
            val marginY = sampleH * marginPct
            var foundEdges = false

            for (y in 1 until sampleH - 1) {
                val row = y * sampleW
                for (x in 1 until sampleW - 1) {
                    val g = grad[row + x]
                    if (g > threshold) {
                        if (x < marginX || x > sampleW - marginX || y < marginY || y > sampleH - marginY) continue
                        
                        foundEdges = true
                        val sum = x.toFloat() + y.toFloat()
                        val diff = x.toFloat() - y.toFloat()
                        
                        if (sum < minSum) { minSum = sum; tl = android.graphics.PointF(x.toFloat() / sampleW, y.toFloat() / sampleH) }
                        if (sum > maxSum) { maxSum = sum; br = android.graphics.PointF(x.toFloat() / sampleW, y.toFloat() / sampleH) }
                        if (diff > maxDiff) { maxDiff = diff; tr = android.graphics.PointF(x.toFloat() / sampleW, y.toFloat() / sampleH) }
                        if (diff < minDiff) { minDiff = diff; bl = android.graphics.PointF(x.toFloat() / sampleW, y.toFloat() / sampleH) }
                    }
                }
            }
            
            if (!foundEdges) return DocumentQuad.defaultQuad()

            // To prevent picking up random noise far away, we can bring the points slightly inwards if they are completely weird,
            // but isQuadValid will handle the sanity check.
            
            return DocumentQuad(tl, tr, br, bl)
        } catch (e: Exception) {
            return DocumentQuad.defaultQuad()
        }
    }

    /**
     * """
    content = content.replace(old_methods, new_methods)

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
else:
    print("Could not find blocks to replace!")
