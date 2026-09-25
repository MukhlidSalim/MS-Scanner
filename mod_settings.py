import re

with open("app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Make sure imports are there for data models
import_block = """import com.example.ui.viewmodel.DocumentViewModel"""
new_imports = """import com.example.ui.viewmodel.DocumentViewModel
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset"""
content = content.replace(import_block, new_imports)

# We will just append the UI sections after "Language Settings" and before "Security & App Lock"
# Wait, let's find the Security & App Lock block and insert it before that.
security_pattern = r"""            // Security & App Lock"""
settings_to_insert = """            // Appearance Settings
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "App Theme", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("System", "Light", "Dark").forEach { theme ->
                            FilterChip(
                                selected = uiState.themeMode == theme,
                                onClick = { viewModel.setThemeMode(theme) },
                                label = { Text(theme) }
                            )
                        }
                    }
                }
            }

            // PDF Defaults
            Text(
                text = "Default PDF Export Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Page Size", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageSizePreset.values().forEach { size ->
                                FilterChip(
                                    selected = uiState.defaultPdfPageSize == size,
                                    onClick = { viewModel.setDefaultPdfPageSize(size) },
                                    label = { Text(size.name) }
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Compression & Quality", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompressionPreset.values().forEach { comp ->
                                FilterChip(
                                    selected = uiState.defaultPdfCompression == comp,
                                    onClick = { viewModel.setDefaultPdfCompression(comp) },
                                    label = { Text(comp.name) }
                                )
                            }
                        }
                    }
                }
            }

            // Cloud Sync & Auto-save (Dummy as per requirements)
            Text(
                text = "Cloud Sync & Backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(text = "Auto-save to Gallery", fontWeight = FontWeight.SemiBold)
                            Text(text = "Save a copy of scanned pages to photos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = false, onCheckedChange = {
                            Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                        })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(text = "Google Drive Sync", fontWeight = FontWeight.SemiBold)
                            Text(text = "Securely backup your documents", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = {
                            Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                            Text("Connect")
                        }
                    }
                }
            }

            // Security & App Lock"""
content = re.sub(security_pattern, settings_to_insert, content)

with open("app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
