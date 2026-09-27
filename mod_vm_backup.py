import re

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

imports = """import com.example.data.repository.BackupManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
"""
content = content.replace('import androidx.lifecycle.viewModelScope', 'import androidx.lifecycle.viewModelScope\n' + imports)

# We need to initialize BackupManager, but we need context. We have applicationContext passed to ViewModel.
init = """
    private val backupManager = BackupManager(context, repository.documentDao)
    
    private val _backupEvent = MutableSharedFlow<String>()
    val backupEvent = _backupEvent.asSharedFlow()

    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.createBackup(uri)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _backupEvent.emit("Backup created successfully")
            } else {
                _backupEvent.emit("Backup failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = backupManager.restoreBackup(uri)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _backupEvent.emit("Restore completed successfully. Restored ${result.getOrNull()} documents.")
                loadAllDocuments()
            } else {
                _backupEvent.emit("Restore failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }
"""

content = content.replace('fun verifyPin(pin: String): Boolean {', init + '\n    fun verifyPin(pin: String): Boolean {')

with open('app/src/main/java/com/example/ui/viewmodel/DocumentViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
