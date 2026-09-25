package com.example.ui.screens.settings

import androidx.compose.ui.res.stringResource
import com.example.R
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

import com.example.ui.theme.CyanScan
import com.example.ui.theme.EmeraldLight
import com.example.ui.viewmodel.DocumentViewModel
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.storageStats

    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }

    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_settings___privacy), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Privacy Card (Zero Tracking / Local Only)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = EmeraldLight,
                        modifier = Modifier.size(36.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.txt_offline_architecture),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = stringResource(R.string.txt_offline_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Language Settings
            Text(
                text = stringResource(R.string.txt_language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            var isArabic by remember { mutableStateOf(currentLocale.contains("ar")) }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = stringResource(R.string.txt_app_language), fontWeight = FontWeight.SemiBold)
                        Text(text = stringResource(R.string.txt_switch_lang_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isArabic,
                        onCheckedChange = { 
                            isArabic = it
                            val newLocales = if (it) androidx.core.os.LocaleListCompat.forLanguageTags("ar") else androidx.core.os.LocaleListCompat.forLanguageTags("en")
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(newLocales)
                        }
                    )
                }
            }

            // Appearance Settings
            Text(
                text = stringResource(R.string.txt_appearance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.txt_app_theme), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val themeOptions = listOf("System" to stringResource(R.string.txt_system), "Light" to stringResource(R.string.txt_light), "Dark" to stringResource(R.string.txt_dark))
                        themeOptions.forEach { (themeId, themeLabel) ->
                            FilterChip(
                                selected = uiState.themeMode == themeId,
                                onClick = { viewModel.setThemeMode(themeId) },
                                label = { Text(themeLabel) }
                            )
                        }
                    }
                }
            }

            // PDF Defaults
            Text(
                text = stringResource(R.string.txt_default_pdf_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = stringResource(R.string.txt_page_size), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageSizePreset.values().forEach { size ->
                                val sizeName = when (size) {
                                    PageSizePreset.A4 -> stringResource(R.string.page_size_a4)
                                    PageSizePreset.LETTER -> stringResource(R.string.page_size_letter)
                                    PageSizePreset.FIT_ORIGINAL -> stringResource(R.string.page_size_fit)
                                    PageSizePreset.LEGAL -> stringResource(R.string.page_size_legal)
                                }
                                FilterChip(
                                    selected = uiState.defaultPdfPageSize == size,
                                    onClick = { viewModel.setDefaultPdfPageSize(size) },
                                    label = { Text(sizeName) }
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = stringResource(R.string.txt_compression_quality), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompressionPreset.values().forEach { comp ->
                                val compName = when (comp) {
                                    CompressionPreset.MAXIMUM -> stringResource(R.string.compression_max)
                                    CompressionPreset.HIGH -> stringResource(R.string.compression_high)
                                    CompressionPreset.MEDIUM -> stringResource(R.string.compression_medium)
                                    CompressionPreset.LOW -> stringResource(R.string.compression_low)
                                }
                                FilterChip(
                                    selected = uiState.defaultPdfCompression == comp,
                                    onClick = { viewModel.setDefaultPdfCompression(comp) },
                                    label = { Text(compName) }
                                )
                            }
                        }
                    }
                }
            }




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

            // Security & App Lock
            Text(
                text = stringResource(R.string.txt_security_pin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.txt_app_pin_protection), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (uiState.hasPinConfigured) stringResource(R.string.txt_pin_configured) else stringResource(R.string.txt_no_pin),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { showPinDialog = true },
                            modifier = Modifier.testTag("set_pin_btn")
                        ) {
                            Text(if (uiState.hasPinConfigured) stringResource(R.string.txt_change_pin) else stringResource(R.string.txt_set_pin))
                        }
                    }

                    if (uiState.hasPinConfigured) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setPin("")
                                Toast.makeText(context, context.getString(R.string.txt_pin_disabled), Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.txt_disable_pin_lock))
                        }
                    }
                }
            }

            // Legal & Info
            Text(
                text = stringResource(R.string.txt_legal),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/privacy"))
                            context.startActivity(intent)
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.txt_privacy_policy), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Storage Management
            Text(
                text = stringResource(R.string.txt_storage_management),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.txt_total_scanned_documents), fontSize = 13.sp)
                        Text("${stats?.totalDocumentsCount ?: 0}", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.txt_total_pages), fontSize = 13.sp)
                        Text("${stats?.totalPagesCount ?: 0}", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.txt_document_scans_storage), fontSize = 13.sp)
                        val scansMb = (stats?.scansSizeBytes ?: 0L) / (1024 * 1024f)
                        Text(String.format("%.1f MB", scansMb), fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.txt_export___temp_cache), fontSize = 13.sp)
                        val cacheMb = (stats?.cacheSizeBytes ?: 0L) / (1024 * 1024f)
                        Text(String.format("%.1f MB", cacheMb), fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearCache()
                                Toast.makeText(context, context.getString(R.string.txt_cache_cleared), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).testTag("clear_cache_btn")
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_clear_cache))
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.emptyTrash()
                                Toast.makeText(context, context.getString(R.string.txt_trash_emptied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).testTag("empty_trash_btn")
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_empty_trash))
                        }
                    }
                }
            }

            // About
            Text(
                text = stringResource(R.string.txt_about),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.txt_docscan_pro_v1_0), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.txt_professional_document_scan),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(stringResource(R.string.txt_configure_4_digit_pin)) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it },
                    label = { Text(stringResource(R.string.txt_enter_4_digits)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pin_setup_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length == 4) {
                            viewModel.setPin(pinInput)
                            showPinDialog = false
                            Toast.makeText(context, context.getString(R.string.txt_pin_set_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.txt_pin_4_digits), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.txt_save_pin))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }
}
