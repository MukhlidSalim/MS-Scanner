package com.example.ui.screens.settings
import androidx.compose.ui.res.stringResource
import com.example.R
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.model.LockType
import com.example.ui.theme.CyanScan
import com.example.ui.theme.Emerald400
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.GoldBase
import com.example.ui.viewmodel.DocumentListViewModel
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.BuildConfig
import com.example.engine.updater.UpdateCheckState

/**
 * Settings, reorganized into clearly labelled sections (same visual language as the rest of the app:
 * rounded 16dp cards, outlineVariant border, titleMedium bold section headers) instead of one long
 * unlabelled scroll. No existing behaviour was removed — every action below calls the exact same
 * ViewModel / AppPreferences / repository function as before; only the grouping and presentation changed,
 * plus the new "Signatures" section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DocumentListViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Current state of the single update system (MainViewModel). */
    updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    onCheckForUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.storageStats
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    LaunchedEffect(Unit) { viewModel.refreshStorageStats() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is com.example.ui.util.UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    // ---------------------------------------------------------------- Signatures (new, reusable vault)
    var showSignatureStudio by remember { mutableStateOf(false) }
    val signatureRepository = remember {
        com.example.data.repository.DocumentRepository(context, com.example.data.db.DocScanDatabase.getInstance(context).documentDao())
    }
    val savedSignatures by signatureRepository.getAllSignatures().collectAsState(initial = emptyList())

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_settings___privacy), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
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
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ======================================================================= Privacy banner
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Default.Shield, MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.txt_offline_architecture),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.txt_offline_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ======================================================================= Appearance (language + theme)
            SectionHeader(stringResource(R.string.txt_appearance))
            SectionCard {
                val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
                var isArabicToggle by remember { mutableStateOf(currentLocale.contains("ar")) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = stringResource(R.string.txt_app_language), fontWeight = FontWeight.SemiBold)
                        Text(text = stringResource(R.string.txt_switch_lang_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isArabicToggle,
                        onCheckedChange = {
                            isArabicToggle = it
                            val newLocales = if (it) androidx.core.os.LocaleListCompat.forLanguageTags("ar") else androidx.core.os.LocaleListCompat.forLanguageTags("en")
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(newLocales)
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(text = stringResource(R.string.txt_app_theme), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val themeOptions = listOf("System" to stringResource(R.string.txt_system), "Light" to stringResource(R.string.txt_light), "Dark" to stringResource(R.string.txt_dark))
                    themeOptions.forEach { (themeId, themeLabel) ->
                        FilterChip(
                            selected = uiState.themeMode == themeId,
                            onClick = { viewModel.setThemeMode(themeId) },
                            shape = RoundedCornerShape(50),
                            label = { Text(themeLabel) }
                        )
                    }
                }
            }

            // ======================================================================= Scanning engine
            SectionHeader(t("Scanning", "المسح الضوئي"))
            SectionCard {
                val appPrefs = remember { com.example.data.repository.AppPreferences(context) }
                var scanEngine by remember { mutableStateOf(appPrefs.scanEngine) }
                val googleSupported = remember { com.example.engine.scanner.GoogleDocumentScanner.isSupported(context) }
                Text(t("Scan engine", "محرك المسح"), fontWeight = FontWeight.SemiBold)
                listOf(
                    com.example.data.repository.AppPreferences.SCAN_ENGINE_GOOGLE to Pair(
                        t("Google scanner (recommended)", "ماسح Google (موصى به)"),
                        t("Most accurate edges, shadow & stain removal, multi-page", "اكتشاف أدق للحواف، إزالة الظلال والبقع، ومسح متعدد الصفحات")
                    ),
                    com.example.data.repository.AppPreferences.SCAN_ENGINE_BUILT_IN to Pair(
                        t("Built-in camera", "الكاميرا المدمجة"),
                        t("Works without Google services, with flash and manual capture settings", "تعمل بدون خدمات Google، مع إعدادات الفلاش والالتقاط اليدوي")
                    )
                ).forEach { (engine, texts) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { scanEngine = engine; appPrefs.scanEngine = engine }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = scanEngine == engine, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(texts.first, fontWeight = FontWeight.Medium)
                            Text(texts.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (scanEngine == com.example.data.repository.AppPreferences.SCAN_ENGINE_GOOGLE && !googleSupported) {
                    Text(
                        t(
                            "The Google scanner is not available on this device; the built-in camera is used automatically.",
                            "ماسح Google غير متاح على هذا الجهاز، وستُستخدم الكاميرا المدمجة تلقائياً."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    t(
                        "ID card and passport always use the built-in camera (framing guides, front & back).",
                        "بطاقة الهوية وجواز السفر يستخدمان الكاميرا المدمجة دائماً (إطارات إرشادية ووجهان)."
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ======================================================================= Signatures (NEW)
            SectionHeader(t("Signatures", "التوقيعات"))
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSignatureStudio = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(Icons.Default.Draw, GoldBase)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("My signatures", "توقيعاتي"), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (savedSignatures.isEmpty()) t("Create your signature once, reuse it anywhere", "أنشئ توقيعك مرة واحدة واستخدمه في كل مكان")
                            else t("${savedSignatures.size} saved — tap to manage", "${savedSignatures.size} محفوظ — اضغط للإدارة"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ======================================================================= Documents & PDF defaults
            SectionHeader(stringResource(R.string.txt_default_pdf_settings))
            SectionCard {
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
                            shape = RoundedCornerShape(50),
                            label = { Text(sizeName) }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                            shape = RoundedCornerShape(50),
                            label = { Text(compName) }
                        )
                    }
                }
            }

            // ======================================================================= OCR
            SectionHeader(t("OCR Settings", "إعدادات استخراج النص (OCR)"))
            SectionCard {
                Text(t("Default Language", "اللغة الافتراضية"), fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.engine.ocr.OcrLanguage.values().forEach { lang ->
                        val label = when (lang) {
                            com.example.engine.ocr.OcrLanguage.AUTO -> t("Auto (Arabic + English)", "تلقائي (عربي + إنجليزي)")
                            com.example.engine.ocr.OcrLanguage.ARABIC -> t("Arabic", "عربي")
                            com.example.engine.ocr.OcrLanguage.ENGLISH -> t("English", "إنجليزي")
                        }
                        FilterChip(
                            selected = uiState.ocrLanguage == lang,
                            onClick = { viewModel.setOcrLanguage(lang) },
                            label = { Text(label) }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t("Arabic recognition model", "نموذج التعرف على العربية"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                uiState.arabicModelReady -> t("Ready — works offline", "جاهز — يعمل بدون إنترنت")
                                uiState.isDownloadingArabicModel -> t("Downloading…", "جاري التحميل…")
                                else -> t("Not downloaded (1.4 MB, once)", "غير محمّل (1.4 ميجابايت، مرة واحدة)")
                            },
                            fontSize = 12.sp,
                            color = if (uiState.arabicModelReady) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.isDownloadingArabicModel) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (!uiState.arabicModelReady) {
                        TextButton(onClick = { viewModel.downloadArabicModel() }) { Text(t("Download", "تحميل")) }
                    }
                }
            }

            // ======================================================================= Security & App Lock
            SectionHeader(t("Security & App Lock", "الأمان وقفل التطبيق"))
            SectionCard {
                Text(t("Lock Type", "نوع القفل"), fontWeight = FontWeight.SemiBold)
                Text(t("Choose how to protect your app", "اختر وسيلة حماية التطبيق"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lockOptions = listOf(
                        LockType.NONE to t("Off", "إيقاف"),
                        LockType.PIN to t("PIN", "رمز PIN"),
                        LockType.BIOMETRIC to t("Biometric", "بصمة")
                    )
                    lockOptions.forEach { (type, label) ->
                        val isSelected = uiState.lockType == type
                        FilterChip(
                            selected = isSelected,
                            onClick = { if (type == LockType.PIN) showPinDialog = true else viewModel.setLockType(type) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // ======================================================================= App Updates
            SectionHeader(t("App Updates", "تحديثات التطبيق"))
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text((t("Current version: ", "الإصدار الحالي: ")) + BuildConfig.VERSION_NAME, fontWeight = FontWeight.SemiBold)
                        Text(
                            t("Checked automatically on launch and every 12 h", "يتم التحقق تلقائياً عند الفتح وكل 12 ساعة"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onCheckForUpdates,
                        enabled = updateCheckState !is UpdateCheckState.Checking,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("check_updates_btn")
                    ) {
                        if (updateCheckState is UpdateCheckState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(t("Check now", "التحقق الآن"))
                        }
                    }
                }
                when (val st = updateCheckState) {
                    is UpdateCheckState.UpToDate -> if (st.isManual) Text(
                        t("You have the latest version ✓", "لديك أحدث إصدار ✓"),
                        color = Emerald400, fontSize = 13.sp
                    )
                    is UpdateCheckState.Error -> if (st.isManual) Text(
                        if (isArabic) st.messageAr else st.messageEn,
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp
                    )
                    is UpdateCheckState.Available -> Text(
                        (t("Version available: ", "يتوفر الإصدار ")) + st.updateInfo.latestVersion,
                        color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
                    )
                    else -> Unit
                }
            }

            // ======================================================================= Backup & Restore
            SectionHeader(stringResource(R.string.txt_backup_restore))
            SectionCard {
                val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    uri?.let { viewModel.createBackup(it) }
                }
                val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { viewModel.restoreBackup(it) }
                }
                if (uiState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            backupLauncher.launch("MS_Scanner_Backup_$dateStr.zip")
                        },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(t("Backup", "نسخ احتياطي")) }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(t("Restore", "استعادة")) }
                }
            }

            // ======================================================================= Storage
            SectionHeader(stringResource(R.string.txt_storage_management))
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.txt_total_scanned_documents), fontSize = 13.sp)
                    Text("${stats?.totalDocumentsCount ?: 0}", fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.txt_total_pages), fontSize = 13.sp)
                    Text("${stats?.totalPagesCount ?: 0}", fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.txt_document_scans_storage), fontSize = 13.sp)
                    val scansMb = (stats?.scansSizeBytes ?: 0L) / (1024 * 1024f)
                    Text(String.format(Locale.US, "%.1f MB", scansMb), fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.txt_export___temp_cache), fontSize = 13.sp)
                    val cacheMb = (stats?.cacheSizeBytes ?: 0L) / (1024 * 1024f)
                    Text(String.format(Locale.US, "%.1f MB", cacheMb), fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("Used Storage", "المساحة المستخدمة"), fontSize = 13.sp)
                    val totalMb = ((stats?.scansSizeBytes ?: 0L) + (stats?.cacheSizeBytes ?: 0L)) / (1024 * 1024f)
                    Text(String.format(Locale.US, "%.1f MB", totalMb), fontWeight = FontWeight.Bold)
                }
                if (uiState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TextButton(onClick = { viewModel.clearCache() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.txt_clear_cache))
                }
            }

            // ======================================================================= Legal & About
            SectionHeader(stringResource(R.string.txt_legal))
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/privacy"))
                            runCatching { context.startActivity(intent) }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.txt_privacy_policy), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    stringResource(R.string.txt_professional_document_scan),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false; pinInput = "" },
            shape = RoundedCornerShape(22.dp),
            title = { Text(stringResource(R.string.txt_configure_4_digit_pin), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it },
                    label = { Text(stringResource(R.string.txt_enter_4_digits)) },
                    singleLine = true,
                    // Digits are never shown on screen.
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("pin_setup_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length == 4) {
                            viewModel.setPin(pinInput)
                            pinInput = ""
                            showPinDialog = false
                            Toast.makeText(context, context.getString(R.string.txt_pin_set_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.txt_pin_4_digits), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.txt_save_pin), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false; pinInput = "" }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.txt_cancel))
                }
            }
        )
    }

    if (showSignatureStudio) {
        SignatureStudioScreen(
            repository = signatureRepository,
            onDismiss = { showSignatureStudio = false }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}
