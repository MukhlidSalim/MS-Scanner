import re

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "r", encoding="utf-8") as f:
    content = f.read()

state_pattern = r"""    val isAppLocked: Boolean = false,
    val userPin: String = "",
    val hasPinConfigured: Boolean = false
\)"""

state_replacement = """    val isAppLocked: Boolean = false,
    val userPin: String = "",
    val hasPinConfigured: Boolean = false,
    val themeMode: String = "System",
    val defaultPdfPageSize: com.example.data.model.PageSizePreset = com.example.data.model.PageSizePreset.A4,
    val defaultPdfCompression: com.example.data.model.CompressionPreset = com.example.data.model.CompressionPreset.HIGH
)"""
content = re.sub(state_pattern, state_replacement, content)


init_pattern = r"""class DocumentViewModel\(
    private val context: Context,
    private val repository: DocumentRepository
\) : ViewModel\(\) \{

    private val _uiState = MutableStateFlow\(DocumentUiState\(\)\)"""

init_replacement = """class DocumentViewModel(
    private val context: Context,
    private val repository: DocumentRepository
) : ViewModel() {

    private val prefs = com.example.data.repository.AppPreferences(context)
    private val _uiState = MutableStateFlow(DocumentUiState(
        userPin = prefs.userPin,
        hasPinConfigured = prefs.userPin.length == 4,
        themeMode = prefs.themeMode,
        defaultPdfPageSize = prefs.pdfPageSize,
        defaultPdfCompression = prefs.pdfCompression
    ))"""
content = re.sub(init_pattern, init_replacement, content)

pin_pattern = r"""    fun setPin\(pin: String\) \{
        _uiState\.update \{ it\.copy\(userPin = pin, hasPinConfigured = pin\.length == 4\) \}
    \}"""

pin_replacement = """    fun setPin(pin: String) {
        prefs.userPin = pin
        _uiState.update { it.copy(userPin = pin, hasPinConfigured = pin.length == 4) }
    }

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDefaultPdfPageSize(size: com.example.data.model.PageSizePreset) {
        prefs.pdfPageSize = size
        _uiState.update { it.copy(defaultPdfPageSize = size) }
    }

    fun setDefaultPdfCompression(comp: com.example.data.model.CompressionPreset) {
        prefs.pdfCompression = comp
        _uiState.update { it.copy(defaultPdfCompression = comp) }
    }"""
content = re.sub(pin_pattern, pin_replacement, content)

with open("app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt", "w", encoding="utf-8") as f:
    f.write(content)
