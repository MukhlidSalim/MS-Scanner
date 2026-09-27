import os

filepath = 'app/src/main/java/com/example/MainActivity.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_stop = """    override fun onStop() {
        super.onStop()
        // Reset authentication state when app goes to background if biometric is enabled
        if (prefs.biometricEnabled) {
            mainViewModel.setAuthenticated(false)
        }
    }"""

new_stop = """    override fun onStop() {
        super.onStop()
        // Reset authentication state when app goes to background if biometric is enabled
        if (prefs.biometricEnabled) {
            mainViewModel.setAuthenticated(false)
            mainViewModel.setPromptShowing(false)
        }
    }"""

content = content.replace(old_stop, new_stop)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
