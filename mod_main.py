import re

with open("app/src/main/java/com/example/MainActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""            val docViewModel = remember \{ DocumentViewModel\(applicationContext, repository\) \}

            DocScanTheme \{"""

replacement = """            val docViewModel = remember { DocumentViewModel(applicationContext, repository) }
            val docUiState by docViewModel.uiState.collectAsState()
            
            val isDarkTheme = when (docUiState.themeMode) {
                "Light" -> false
                "Dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            DocScanTheme(darkTheme = isDarkTheme) {"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(content)
