import re

with open('app/src/main/java/com/example/engine/annotation/AnnotationEngine.kt', 'r', encoding='utf-8') as f:
    content = f.read()

placed_text = """
data class PlacedText(
    val text: String,
    val x: Float, // 0f..1f normalized
    val y: Float, // 0f..1f normalized
    val color: Int = android.graphics.Color.BLACK,
    val textSize: Float = 48f
)
"""

content = content.replace('object AnnotationEngine {', placed_text + '\nobject AnnotationEngine {')

old_sig = """    fun burnAnnotationsIntoBitmap(
        baseBitmap: Bitmap,
        paths: List<DrawPath>,
        redactions: List<RedactionRect>,
        placedSignatures: List<PlacedSignature>
    ): Bitmap {"""

new_sig = """    fun burnAnnotationsIntoBitmap(
        baseBitmap: Bitmap,
        paths: List<DrawPath>,
        redactions: List<RedactionRect>,
        placedSignatures: List<PlacedSignature>,
        placedTexts: List<PlacedText> = emptyList(),
        brightness: Float = 0f,
        contrast: Float = 1f
    ): Bitmap {"""

content = content.replace(old_sig, new_sig)

old_body_start = """        val output = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()"""

new_body_start = """        val output = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()

        val cm = android.graphics.ColorMatrix()
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + (brightness * 255f / 100f) // normalized brightness to 0-255
        val array = FloatArray(20)
        array[0] = scale; array[4] = translate
        array[6] = scale; array[9] = translate
        array[12] = scale; array[14] = translate
        array[18] = 1f
        cm.set(array)
        val paintBase = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(baseBitmap, 0f, 0f, paintBase)"""

content = content.replace(old_body_start, new_body_start)

draw_text = """
        // 4. Draw placed texts
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        for (pt in placedTexts) {
            textPaint.color = pt.color
            textPaint.textSize = pt.textSize * (w / 1080f) // scale with image width
            canvas.drawText(pt.text, pt.x * w, pt.y * h, textPaint)
        }
"""

content = content.replace('        return output', draw_text + '        return output')

with open('app/src/main/java/com/example/engine/annotation/AnnotationEngine.kt', 'w', encoding='utf-8') as f:
    f.write(content)
