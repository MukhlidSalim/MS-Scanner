import os

filepath = 'app/src/main/java/com/example/engine/cv/ImageProcessor.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_block = """        // JPEG compression: 90 for text/documents, 75 for photos/raw images
        val quality = if (isTextContent) 90 else 75"""

new_block = """        // Auto-detect based on prefix if it's raw or photo
        val actualIsText = isTextContent && !prefix.contains("raw", ignoreCase = true) && !prefix.contains("thumb", ignoreCase = true)
        
        // JPEG compression: 90 for text/documents, 75 for photos/raw images
        val quality = if (actualIsText) 90 else 75"""

content = content.replace(old_block, new_block)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
