import re
with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("var showCameraSheet by remember { mutableStateOf(false) }", "var showCameraSheet by remember { mutableStateOf(false) }\n    var isIdCardMode by remember { mutableStateOf(false) }")

scanner_launch_old = """    val launchScanner = {
        val activity = context as? Activity
        if (activity != null) {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener {
                    onNavigateToScan() // Fallback
                }
        }
    }"""

scanner_launch_new = """    val launchScanner: (Int) -> Unit = { limit ->
        val activity = context as? Activity
        if (activity != null) {
            val dynOptions = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(limit)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(dynOptions).getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener {
                    onNavigateToScan() // Fallback
                }
        }
    }"""

content = content.replace(scanner_launch_old, scanner_launch_new)
content = content.replace("onClick = { launchScanner() }", "onClick = { showCameraSheet = true }")

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
