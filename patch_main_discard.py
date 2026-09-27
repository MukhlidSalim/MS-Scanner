import os

filepath = 'app/src/main/java/com/example/MainActivity.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'onClearPending = { cameraViewModel.clearPendingPages() }',
    'onClearPending = { cameraViewModel.clearPendingPages(deleteFiles = true) }'
)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
