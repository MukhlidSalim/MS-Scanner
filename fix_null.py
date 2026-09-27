import os
import re

def fix_nullable(filepath):
    if not os.path.exists(filepath): return
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix isNotBlank()
    content = content.replace('document.thumbnailPath.isNotBlank()', '!document.thumbnailPath.isNullOrBlank()')
    content = content.replace('doc.thumbnailPath.isNotEmpty()', '!doc.thumbnailPath.isNullOrEmpty()')
    
    # Fix File(...)
    content = content.replace('File(document.thumbnailPath)', 'File(document.thumbnailPath!!)')
    content = content.replace('File(doc.thumbnailPath)', 'File(doc.thumbnailPath!!)')
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

fix_nullable('app/src/main/java/com/example/ui/components/DocumentCard.kt')
fix_nullable('app/src/main/java/com/example/ui/screens/home/components/HomeGridItems.kt')
