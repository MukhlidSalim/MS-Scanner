import os

filepath = 'app/src/main/java/com/example/ui/screens/editor/EditSessionScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -1f..1f)',
    'Slider(value = brightness, onValueChange = { brightness = it; hasUnsavedChanges = true }, valueRange = -1f..1f)'
)

content = content.replace(
    'Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0f..2f)',
    'Slider(value = contrast, onValueChange = { contrast = it; hasUnsavedChanges = true }, valueRange = 0f..2f)'
)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
