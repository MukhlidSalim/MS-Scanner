import re

with open('app/src/main/java/com/example/ui/screens/annotate/AnnotationScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update AnnotateTool
content = content.replace(
    'enum class AnnotateTool {\n    PEN,\n    HIGHLIGHTER,\n    REDACT,\n    SIGNATURE,\n    ERASER\n}',
    'enum class AnnotateTool {\n    PEN,\n    HIGHLIGHTER,\n    REDACT,\n    SIGNATURE,\n    ERASER,\n    TEXT,\n    BRIGHTNESS,\n    CONTRAST\n}'
)

# 2. Add State and imports
imports = """import com.example.engine.annotation.PlacedText
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
"""
content = content.replace('import androidx.compose.ui.geometry.Offset', imports + 'import androidx.compose.ui.geometry.Offset')

state_to_add = """    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    val placedTexts = remember { mutableStateListOf<PlacedText>() }
    var showTextDialog by remember { mutableStateOf(false) }
    var tempTextInput by remember { mutableStateOf("") }
"""
content = content.replace('var penColor by ', state_to_add + '    var penColor by ')

# 3. Update top bar save
content = content.replace(
    'viewModel.saveAnnotations(paths, redactions, placedSignatures)',
    'viewModel.saveAnnotations(paths, redactions, placedSignatures, placedTexts.toList(), brightness, contrast)'
)

# 4. ColorMatrix for base image
color_matrix = """                        val cm = ColorMatrix()
                        val scale = contrast
                        val translate = (-0.5f * scale + 0.5f) * 255f + (brightness * 255f / 100f)
                        val array = FloatArray(20)
                        array[0] = scale; array[4] = translate
                        array[6] = scale; array[9] = translate
                        array[12] = scale; array[14] = translate
                        array[18] = 1f
                        cm.values.indices.forEach { i -> cm.values[i] = array[i] }
                        
                        drawImage(
                            image = baseBitmap!!.asImageBitmap(),
                            colorFilter = ColorMatrixColorFilter(cm),
                            dstSize = IntSize(renderW.toInt(), renderH.toInt()),
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt())
                        )"""

content = re.sub(
    r'drawImage\(\s*image = baseBitmap!!\.asImageBitmap\(\),\s*dstSize = IntSize\(renderW\.toInt\(\), renderH\.toInt\(\)\),\s*dstOffset = androidx\.compose\.ui\.unit\.IntOffset\(offsetX\.toInt\(\), offsetY\.toInt\(\)\)\s*\)',
    color_matrix,
    content
)

# 5. Draw PlacedTexts
draw_texts = """
                        // 4.5 Draw Placed Texts
                        for (pt in placedTexts) {
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    color = pt.color
                                    textSize = pt.textSize * (renderW / 1080f)
                                }
                                drawText(pt.text, offsetX + pt.x * renderW, offsetY + pt.y * renderH, paint)
                            }
                        }
"""
content = content.replace('// 5. Draw Redaction Rectangles', draw_texts + '\n                        // 5. Draw Redaction Rectangles')

# 6. Bottom Bar Tools
tools_ui = """
                    // Sliders for Brightness/Contrast
                    if (activeTool == AnnotateTool.BRIGHTNESS) {
                        Column {
                            Text("Brightness", style = MaterialTheme.typography.labelSmall)
                            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)
                        }
                    } else if (activeTool == AnnotateTool.CONTRAST) {
                        Column {
                            Text("Contrast", style = MaterialTheme.typography.labelSmall)
                            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2.0f)
                        }
                    }

                    // Tool Selection Row
"""
content = content.replace('// Tool Selection Row', tools_ui)

tools_buttons = """
                        IconButton(onClick = { activeTool = AnnotateTool.BRIGHTNESS }) {
                            Icon(Icons.Default.BrightnessMedium, contentDescription = "Brightness", tint = if (activeTool == AnnotateTool.BRIGHTNESS) EmeraldLight else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { activeTool = AnnotateTool.CONTRAST }) {
                            Icon(Icons.Default.Contrast, contentDescription = "Contrast", tint = if (activeTool == AnnotateTool.CONTRAST) EmeraldLight else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { 
                            activeTool = AnnotateTool.TEXT
                            showTextDialog = true 
                        }) {
                            Icon(Icons.Default.TextFields, contentDescription = "Text", tint = if (activeTool == AnnotateTool.TEXT) EmeraldLight else MaterialTheme.colorScheme.onSurface)
                        }
"""
content = content.replace('IconButton(onClick = { activeTool = AnnotateTool.ERASER }) {', tools_buttons + '\n                        IconButton(onClick = { activeTool = AnnotateTool.ERASER }) {')


# 7. Text Dialog
text_dialog = """
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Add Text") },
            text = {
                OutlinedTextField(
                    value = tempTextInput,
                    onValueChange = { tempTextInput = it },
                    label = { Text("Enter text") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (tempTextInput.isNotBlank()) {
                        placedTexts.add(PlacedText(text = tempTextInput, x = 0.5f, y = 0.5f, color = android.graphics.Color.RED))
                        tempTextInput = ""
                    }
                    showTextDialog = false
                }) { Text("Add") }
            }
        )
    }
"""
content = content.replace('    if (showSignatureDialog) {', text_dialog + '\n    if (showSignatureDialog) {')


with open('app/src/main/java/com/example/ui/screens/annotate/AnnotationScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
