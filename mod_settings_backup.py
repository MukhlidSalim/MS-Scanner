import re

with open('app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

imports = """import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
"""
content = content.replace('import androidx.compose.ui.unit.sp', 'import androidx.compose.ui.unit.sp\n' + imports)

backup_ui = """
            // Backup & Restore
            Text(
                text = stringResource(R.string.txt_backup_restore),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        uri?.let { viewModel.createBackup(it) }
                    }
                    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let { viewModel.restoreBackup(it) }
                    }
                    
                    LaunchedEffect(Unit) {
                        viewModel.backupEvent.collectLatest { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.txt_create_backup), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.txt_create_backup_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { 
                            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            backupLauncher.launch("MS_Scanner_Backup_$dateStr.zip")
                        }) {
                            Text("Backup")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.txt_restore_backup), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.txt_restore_backup_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Text("Restore")
                        }
                    }
                }
            }
"""

content = content.replace('            // Security & App Lock', backup_ui + '\n            // Security & App Lock')

with open('app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
