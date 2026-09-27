import os

filepath = 'app/src/main/java/com/example/ui/screens/camera/CameraScanScreen.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

import re

# Remove the permission check for gallery
old_launch = """    val launchGalleryImport = {
        val permissionCheck = ContextCompat.checkSelfPermission(context, galleryPermission)
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            galleryPermissionLauncher.launch(galleryPermission)
        }
    }"""

new_launch = """    val launchGalleryImport = {
        // Photo Picker does not require permissions!
        galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }"""

content = content.replace(old_launch, new_launch)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
