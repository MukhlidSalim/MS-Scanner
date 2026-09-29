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
import com.example.ui.viewmodel.DocumentListViewModel
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.BuildConfig
import com.example.engine.updater.UpdateCheckState
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
    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Privacy Card (Zero Tracking / Local Only)
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
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
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
            // Language Settings
            Text(
                text = stringResource(R.string.txt_language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            var isArabic by remember { mutableStateOf(currentLocale.contains("ar")) }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
            // PDF Defaults
            Text(
                text = stringResource(R.string.txt_default_pdf_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                    shape = RoundedCornerShape(50),
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
                                    shape = RoundedCornerShape(50),
                                    label = { Text(compName) }
                                )
                            }
                        }
                    }
                }
            }
            // App Lock Settings
            Text(
                text = if (isArabic) "قفل التطبيق" else "App Lock",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(if (isArabic) "نوع القفل" else "Lock Type", fontWeight = FontWeight.SemiBold)
                            Text(if (isArabic) "اختر وسيلة حماية التطبيق" else "Choose how to protect your app", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val lockOptions = listOf(
                            LockType.NONE to (if (isArabic) "إيقاف" else "Off"),
                            LockType.PIN to (if (isArabic) "رمز PIN" else "PIN"),
                            LockType.BIOMETRIC to (if (isArabic) "بصمة" else "Biometric")
                        )
                        lockOptions.forEach { pair ->
                            val type = pair.first
                            val label = pair.second
                            val isSelected = uiState.lockType == type
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (type == LockType.PIN) showPinDialog = true
                                    else viewModel.setLockType(type)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
            // Scanning engine (persisted). Google = ML Kit Document Scanner, built-in = in-app camera.
            val appPrefs = remember { com.example.data.repository.AppPreferences(context) }
            var scanEngine by remember { mutableStateOf(appPrefs.scanEngine) }
            val googleSupported = remember { com.example.engine.scanner.GoogleDocumentScanner.isSupported(context) }
            Text(
                text = if (isArabic) "المسح الضوئي" else "Scanning",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isArabic) "محرك المسح" else "Scan engine", fontWeight = FontWeight.SemiBold)
                    listOf(
                        com.example.data.repository.AppPreferences.SCAN_ENGINE_GOOGLE to Pair(
                            if (isArabic) "ماسح Google (موصى به)" else "Google scanner (recommended)",
                            if (isArabic) "اكتشاف أدق للحواف، إزالة الظلال والبقع، ومسح متعدد الصفحات" else "Most accurate edges, shadow & stain removal, multi-page"
                        ),
                        com.example.data.repository.AppPreferences.SCAN_ENGINE_BUILT_IN to Pair(
                            if (isArabic) "الكاميرا المدمجة" else "Built-in camera",
                            if (isArabic) "تعمل بدون خدمات Google، مع إعدادات الفلاش والالتقاط اليدوي" else "Works without Google services, with flash and manual capture settings"
                        )
                    ).forEach { (engine, texts) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    scanEngine = engine
                                    appPrefs.scanEngine = engine
                                }
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
                            if (isArabic) "ماسح Google غير متاح على هذا الجهاز، وستُستخدم الكاميرا المدمجة تلقائياً."
                            else "The Google scanner is not available on this device; the built-in camera is used automatically.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        if (isArabic) "بطاقة الهوية وجواز السفر يستخدمان الكاميرا المدمجة دائماً (إطارات إرشادية ووجهان)."
                        else "ID card and passport always use the built-in camera (framing guides, front & back).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // OCR Settings
            Text(
                text = if (isArabic) "إعدادات استخراج النص (OCR)" else "OCR Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isArabic) "اللغة الافتراضية" else "Default Language", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.example.engine.ocr.OcrLanguage.values().forEach { lang ->
                            val label = when (lang) {
                                com.example.engine.ocr.OcrLanguage.AUTO -> if (isArabic) "تلقائي (عربي + إنجليزي)" else "Auto (Arabic + English)"
                                com.example.engine.ocr.OcrLanguage.ARABIC -> if (isArabic) "عربي" else "Arabic"
                                com.example.engine.ocr.OcrLanguage.ENGLISH -> if (isArabic) "إنجليزي" else "English"
                            }
                            FilterChip(
                                selected = uiState.ocrLanguage == lang,
                                onClick = { viewModel.setOcrLanguage(lang) },
                                label = { Text(label) }
                            )
                        }
                    }
                    // Arabic model status (Tesseract, ~1.4 MB, stored once on the device).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isArabic) "نموذج التعرف على العربية" else "Arabic recognition model", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    uiState.arabicModelReady -> if (isArabic) "جاهز — يعمل بدون إنترنت" else "Ready — works offline"
                                    uiState.isDownloadingArabicModel -> if (isArabic) "جاري التحميل…" else "Downloading…"
                                    else -> if (isArabic) "غير محمّل (1.4 ميجابايت، مرة واحدة)" else "Not downloaded (1.4 MB, once)"
                                },
                                fontSize = 12.sp,
                                color = if (uiState.arabicModelReady) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.isDownloadingArabicModel) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (!uiState.arabicModelReady) {
                            TextButton(onClick = { viewModel.downloadArabicModel() }) { Text(if (isArabic) "تحميل" else "Download") }
                        }
                    }
                }
            }
            // App updates (GitHub Releases): automatic check on launch + every 12 h, and on demand here.
            Text(
                text = if (isArabic) "تحديثات التطبيق" else "App Updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (if (isArabic) "الإصدار الحالي: " else "Current version: ") + BuildConfig.VERSION_NAME,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (isArabic) "يتم التحقق تلقائياً عند الفتح وكل 12 ساعة" else "Checked automatically on launch and every 12 h",
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
                                Text(if (isArabic) "التحقق الآن" else "Check now")
                            }
                        }
                    }
                    when (val st = updateCheckState) {
                        is UpdateCheckState.UpToDate -> if (st.isManual) Text(
                            if (isArabic) "لديك أحدث إصدار ✓" else "You have the latest version ✓",
                            color = Emerald400, fontSize = 13.sp
                        )
                        is UpdateCheckState.Error -> if (st.isManual) Text(
                            if (isArabic) st.messageAr else st.messageEn,
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp
                        )
                        is UpdateCheckState.Available -> Text(
                            (if (isArabic) "يتوفر الإصدار " else "Version available: ") + st.updateInfo.latestVersion,
                            color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
                        )
                        else -> Unit
                    }
                }
            }
            // Advanced: Backup & Restore
            Text(
                text = if (isArabic) "خيارات متقدمة" else "Advanced",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        uri?.let { viewModel.createBackup(it) }
                    }
                    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let { viewModel.restoreBackup(it) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.txt_backup_restore), fontWeight = FontWeight.SemiBold)
                            Text(text = if (isArabic) "نسخ احتياطي واستعادة البيانات" else "Create or restore a backup", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                        ) { Text(if (isArabic) "نسخ احتياطي" else "Backup") }
                        OutlinedButton(
                            onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (isArabic) "استعادة" else "Restore") }
                    }
                    if (uiState.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // Storage Stats
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isArabic) "المساحة المستخدمة" else "Used Storage", fontSize = 13.sp)
                        val totalMb = ((stats?.scansSizeBytes ?: 0L) + (stats?.cacheSizeBytes ?: 0L)) / (1024 * 1024f)
                        Text(String.format(Locale.US, "%.1f MB", totalMb), fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { viewModel.clearCache() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.txt_clear_cache))
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/privacy"))
                            runCatching { context.startActivity(intent) }
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                        Text(String.format(Locale.US, "%.1f MB", scansMb), fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.txt_export___temp_cache), fontSize = 13.sp)
                        val cacheMb = (stats?.cacheSizeBytes ?: 0L) / (1024 * 1024f)
                        Text(String.format(Locale.US, "%.1f MB", cacheMb), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearCache()
                                Toast.makeText(context, context.getString(R.string.txt_cache_cleared), Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("clear_cache_btn")
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_clear_cache), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.emptyTrash()
                                Toast.makeText(context, context.getString(R.string.txt_trash_emptied), Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("empty_trash_btn")
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_empty_trash), fontSize = 12.sp)
                        }
                    }
                }
            }
            // Diagnostics: local crash log (never sent automatically).
            var crashCount by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                crashCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.util.CrashReporter.reports(context).size
                }
            }
            Text(
                text = if (isArabic) "التشخيص" else "Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (crashCount == 0) (if (isArabic) "لا توجد تقارير أعطال" else "No crash reports")
                        else (if (isArabic) "تقارير الأعطال المحفوظة: $crashCount" else "Saved crash reports: $crashCount"),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (isArabic) "تُحفظ على الجهاز فقط ولا تُرسل إلا إذا شاركتها أنت." else "Stored on the device only; sent only if you share them.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (crashCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                val file = com.example.util.CrashReporter.exportForSharing(context)
                                if (file != null) com.example.engine.pdf.PdfEngine.shareFiles(context, listOf(file), "text/plain")
                            }, shape = RoundedCornerShape(12.dp)) { Text(if (isArabic) "مشاركة" else "Share") }
                            TextButton(onClick = {
                                com.example.util.CrashReporter.clear(context)
                                crashCount = 0
                            }) { Text(if (isArabic) "حذف" else "Clear") }
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Real version (was hard-coded "v1.0").
                    Text(stringResource(R.string.app_name) + " v" + BuildConfig.VERSION_NAME, fontWeight = FontWeight.Bold)
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
}
