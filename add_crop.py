import re
with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

new_state = """    var showFilterSheet by remember { mutableStateOf(false) }"""
new_state_with_crop = """    var showFilterSheet by remember { mutableStateOf(false) }
    
    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                coroutineScope.launch {
                    val stream = context.contentResolver.openInputStream(uri)
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) {
                        val path = com.example.engine.cv.ImageProcessor.saveBitmapToFile(context, bmp, "crop_proc_")
                        viewModel.updateActivePageProcessedImage(path)
                        bmp.recycle()
                    }
                }
            }
        }
    }"""
content = content.replace(new_state, new_state_with_crop)

crop_btn = """                    // OCR & AI Text"""
crop_btn_new = """                    // Crop
                    IconButton(onClick = {
                        val activePage = pages.getOrNull(pagerState.currentPage) ?: return@IconButton
                        val file = java.io.File(activePage.processedImagePath)
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                        
                        val intent = android.content.Intent("com.android.camera.action.CROP").apply {
                            setDataAndType(uri, "image/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            putExtra("crop", "true")
                            putExtra("return-data", false)
                            val outFile = java.io.File(context.cacheDir, "crop_temp.jpg")
                            val outUri = android.net.Uri.fromFile(outFile)
                            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outUri)
                        }
                        try {
                            cropLauncher.launch(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Device does not support native crop", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Crop, contentDescription = "Crop", tint = CyanScan)
                            Text("Crop", fontSize = 10.sp)
                        }
                    }
                    
                    // OCR & AI Text"""
content = content.replace(crop_btn, crop_btn_new)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
