package com.example
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.db.DocScanDatabase
import com.example.data.repository.DocumentRepository
import com.example.ui.components.PinLockScreen
import com.example.ui.components.OpenPdfActionDialog
import com.example.ui.components.PdfViewerOverlay
import com.example.ui.components.DefaultPdfAppBanner
import com.example.ui.components.PdfReaderToolsSheet
import com.example.ui.components.PdfPasswordDialog
import com.example.engine.pdf.DefaultPdfApp
import com.example.engine.pdf.ExternalPdfImporter
import com.example.engine.pdf.PdfCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.example.ui.navigation.Screen
import com.example.ui.screens.annotate.AnnotationScreen
import com.example.ui.screens.camera.CameraScanScreen
import com.example.ui.screens.camera.ScanCameraMode
import com.example.ui.screens.editor.DocumentCropEditorScreen
import com.example.ui.screens.editor.EditSessionScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.idcard.IdCardMergerScreen
import com.example.ui.screens.ocr.OcrScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.viewer.DocumentViewerScreen
import com.example.ui.theme.DocScanTheme
import com.example.ui.viewmodel.CameraViewModel
import com.example.ui.viewmodel.CaptureTarget
import com.example.ui.viewmodel.DocumentListViewModel
import com.example.ui.viewmodel.EditSessionViewModel
import com.example.data.model.FilterType
import com.example.data.model.LockType
import com.example.engine.cv.DocumentQuad
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.data.repository.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.engine.updater.AppUpdateInfo
import com.example.engine.updater.GitHubUpdateManager
import com.example.engine.updater.UpdateCheckWorker
import com.example.engine.updater.UpdateInstaller
import com.example.engine.scanner.GoogleDocumentScanner
import com.example.engine.scanner.findActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.repeatOnLifecycle
import com.example.engine.updater.UpdateCheckState
import com.example.engine.updater.UpdateDownloadState
import com.example.engine.updater.UpdateSignatureMismatchException
import com.example.ui.components.AppUpdateDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
class AppViewModelFactory(
    private val context: android.content.Context,
    private val repository: com.example.data.repository.DocumentRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CameraViewModel(context, repository) as T
        }
        if (modelClass.isAssignableFrom(DocumentListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DocumentListViewModel(context, repository) as T
        }
        if (modelClass.isAssignableFrom(EditSessionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EditSessionViewModel(context, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
/**
 * Activity-scoped state: app lock + the single update system (GitHubUpdateManager).
 * AndroidViewModel so the default factory (ViewModelProvider(this)) can create it.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val updater = GitHubUpdateManager(application)
    // ------------------------------------------------------------------ lock
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()
    private var _isPromptShowing = false
    val isPromptShowing: Boolean get() = _isPromptShowing
    private var backgroundedAt = 0L
    private var externalFlowActive = false
    fun setAuthenticated(value: Boolean) { _isAuthenticated.value = value }
    fun setPromptShowing(value: Boolean) { _isPromptShowing = value }
    fun onAppBackgrounded() { backgroundedAt = SystemClock.elapsedRealtime() }
    fun beginExternalFlow() { externalFlowActive = true }
    fun endExternalFlow() {
        externalFlowActive = false
        backgroundedAt = SystemClock.elapsedRealtime()
    }
    fun onAppForegrounded(lockEnabled: Boolean) {
        if (!lockEnabled) {
            _isAuthenticated.value = true
            return
        }
        if (externalFlowActive) return
        val away = if (backgroundedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - backgroundedAt
        if (away > LOCK_GRACE_MS) {
            _isAuthenticated.value = false
            _isPromptShowing = false
        }
    }
    // ------------------------------------------------------------------ incoming files
    sealed class IncomingImport {
        data class Pdf(val uri: Uri) : IncomingImport()
        data class Images(val uris: List<Uri>) : IncomingImport()
        object DefaultAppCheck : IncomingImport()
    }
    private val _incomingImport = MutableStateFlow<IncomingImport?>(null)
    val incomingImport: StateFlow<IncomingImport?> = _incomingImport.asStateFlow()
    fun offerIncoming(item: IncomingImport?) { if (item != null) _incomingImport.value = item }
    fun consumeIncoming(): IncomingImport? = _incomingImport.value.also { _incomingImport.value = null }
    // ------------------------------------------------------------------ updates
    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()
    private val _updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState: StateFlow<UpdateDownloadState> = _updateDownloadState.asStateFlow()
    private var downloadJob: Job? = null
    fun checkForUpdates(manual: Boolean = false) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank() || _updateCheckState.value is UpdateCheckState.Checking) return
        if (!manual && System.currentTimeMillis() - prefs.lastUpdateCheckAt < UPDATE_INTERVAL_MS) return
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.Checking
            try {
                val info = updater.checkUpdate(repo)
                prefs.lastUpdateCheckAt = System.currentTimeMillis()
                val skipped = !manual && info.latestVersion == prefs.ignoredUpdateVersion
                _updateCheckState.value = if (info.isUpdateAvailable && !skipped) {
                    UpdateCheckState.Available(info, manual)
                } else {
                    UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME, manual)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _updateCheckState.value = UpdateCheckState.Error(
                    "تعذر التحقق من التحديثات", "Could not check for updates", manual
                )
            }
        }
    }
    fun startUpdateDownload(info: AppUpdateInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _updateDownloadState.value = UpdateDownloadState.Downloading(0L, info.apkSize, 0f)
            var lastPercent = -1
            try {
                val apk = updater.downloadApk(info.downloadUrl, info.latestVersion) { done, total, progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _updateDownloadState.value = UpdateDownloadState.Downloading(done, total, progress)
                    }
                }
                _updateDownloadState.value = if (updater.canInstallPackages()) {
                    UpdateDownloadState.ReadyToInstall(apk, info)
                } else {
                    UpdateDownloadState.PermissionRequired(apk, info)
                }
            } catch (e: CancellationException) {
                _updateDownloadState.value = UpdateDownloadState.Idle
                throw e
            } catch (e: UpdateSignatureMismatchException) {
                _updateDownloadState.value = UpdateDownloadState.Error(
                    "التحديث موقّع بمفتاح مختلف عن النسخة المثبتة. ثبّت النسخة الجديدة يدوياً بعد إزالة الحالية.",
                    "The update is signed with a different key than the installed app. Uninstall and install it manually."
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _updateDownloadState.value = UpdateDownloadState.Error(
                    "فشل تحميل التحديث أو التحقق منه", "Update download or verification failed"
                )
            }
        }
    }
    fun installUpdate(apk: java.io.File) {
        val info = when (val st = _updateDownloadState.value) {
            is UpdateDownloadState.ReadyToInstall -> st.updateInfo
            is UpdateDownloadState.PermissionRequired -> st.updateInfo
            else -> return
        }
        if (!updater.canInstallPackages()) {
            _updateDownloadState.value = UpdateDownloadState.PermissionRequired(apk, info)
            runCatching { getApplication<Application>().startActivity(updater.getInstallPermissionIntent()) }
            return
        }
        viewModelScope.launch {
            try {
                UpdateInstaller.install(getApplication(), apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    updater.launchInstallApk(apk)
                } catch (e2: Exception) {
                    _updateDownloadState.value = UpdateDownloadState.Error("تعذر فتح المثبّت", "Could not open the installer")
                }
            }
        }
    }
    fun onInstallFailed(signatureMismatch: Boolean, message: String) {
        _updateDownloadState.value = if (signatureMismatch) {
            UpdateDownloadState.Error(
                "لا يمكن التحديث فوق النسخة الحالية لأنها موقّعة بمفتاح مختلف (نسخة تجريبية قديمة). أزل النسخة الحالية مرة واحدة ثم ثبّت الجديدة؛ التحديثات بعدها تتم تلقائياً.",
                "The installed copy is signed with a different key (old test build). Uninstall it once and install the new version; later updates install in place."
            )
        } else {
            UpdateDownloadState.Error("فشل التثبيت: $message", "Installation failed: $message")
        }
    }
    fun onResumeCheckInstallPermission() {
        val st = _updateDownloadState.value
        if (st is UpdateDownloadState.PermissionRequired && updater.canInstallPackages()) {
            _updateDownloadState.value = UpdateDownloadState.ReadyToInstall(st.apkFile, st.updateInfo)
        }
    }
    fun dismissUpdate(ignoreVersion: Boolean) {
        (_updateCheckState.value as? UpdateCheckState.Available)?.let {
            if (ignoreVersion) prefs.ignoredUpdateVersion = it.updateInfo.latestVersion
        }
        downloadJob?.cancel()
        _updateCheckState.value = UpdateCheckState.Idle
        _updateDownloadState.value = UpdateDownloadState.Idle
    }
    private companion object {
        const val LOCK_GRACE_MS = 30_000L
        const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences
    private lateinit var mainViewModel: MainViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = AppPreferences(applicationContext)
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        if (!isLockEnabled()) mainViewModel.setAuthenticated(true)
        mainViewModel.checkForUpdates(manual = intent?.getBooleanExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATE, false) == true)
        UpdateCheckWorker.schedule(applicationContext)
        if (savedInstanceState == null) mainViewModel.offerIncoming(parseIncoming(intent))
        requestNotificationPermissionOnce()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateInstaller.events.collect { event ->
                    when (event) {
                        is UpdateInstaller.InstallEvent.NeedsConfirmation -> {
                            UpdateInstaller.consume()
                            runCatching { startActivity(event.intent) }
                        }
                        is UpdateInstaller.InstallEvent.Failed -> {
                            UpdateInstaller.consume()
                            mainViewModel.onInstallFailed(event.signatureMismatch, event.message)
                        }
                        UpdateInstaller.InstallEvent.Success -> UpdateInstaller.consume()
                        null -> Unit
                    }
                }
            }
        }
        val database = DocScanDatabase.getInstance(applicationContext)
        val repository = DocumentRepository(applicationContext, database.documentDao())
        setContent {
            val factory = remember { AppViewModelFactory(applicationContext, repository) }
            val cameraViewModel: CameraViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val listViewModel: DocumentListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val editViewModel: EditSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val listUiState by listViewModel.uiState.collectAsState()
            val isDarkTheme = when (listUiState.themeMode) {
                "Light" -> false
                "Dark" -> true
                else -> isSystemInDarkTheme()
            }
            val isAuthenticated by mainViewModel.isAuthenticated.collectAsState()
            val updateCheckState by mainViewModel.updateCheckState.collectAsState()
            val updateDownloadState by mainViewModel.updateDownloadState.collectAsState()
            DocScanTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isAuthenticated) {
                        DocScanApp(
                            cameraViewModel = cameraViewModel,
                            listViewModel = listViewModel,
                            editViewModel = editViewModel,
                            mainViewModel = mainViewModel
                        )
                        if (updateCheckState is UpdateCheckState.Available) {
                            AppUpdateDialog(
                                checkState = updateCheckState,
                                downloadState = updateDownloadState,
                                onStartDownload = { info -> mainViewModel.startUpdateDownload(info) },
                                onInstallApk = { apk -> mainViewModel.installUpdate(apk) },
                                onDismiss = { ignore -> mainViewModel.dismissUpdate(ignore) }
                            )
                        }
                    } else {
                        LockScreen(listViewModel)
                    }
                }
            }
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.notificationPermissionAsked) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        prefs.notificationPermissionAsked = true
        runCatching { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    private fun parseIncoming(intent: android.content.Intent?): MainViewModel.IncomingImport? {
        if (intent == null) return null
        val action = intent.action ?: return null
        val uris: List<Uri> = when (action) {
            android.content.Intent.ACTION_VIEW, android.content.Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            android.content.Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            )
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return null
        if (uris.size == 1 && DefaultPdfApp.isProbeUri(this, uris[0])) return MainViewModel.IncomingImport.DefaultAppCheck
        val type = intent.type.orEmpty()
        fun typeOf(u: Uri) = runCatching { contentResolver.getType(u) }.getOrNull() ?: type
        fun isPdf(u: Uri): Boolean {
            val t = typeOf(u).lowercase()
            return t == "application/pdf" || t == "application/x-pdf" || t == "application/acrobat" ||
                u.toString().endsWith(".pdf", true) ||
                (t == "application/octet-stream" && (u.lastPathSegment?.endsWith(".pdf", true) == true))
        }
        val pdf = uris.firstOrNull { isPdf(it) }
        val images = uris.filter { typeOf(it).startsWith("image/") }
        return when {
            images.isNotEmpty() -> MainViewModel.IncomingImport.Images(images)
            pdf != null -> MainViewModel.IncomingImport.Pdf(pdf)
            else -> null
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel.offerIncoming(parseIncoming(intent))
        if (intent.getBooleanExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATE, false)) mainViewModel.checkForUpdates(manual = true)
    }
    private fun isLockEnabled(): Boolean = when (prefs.lockType) {
        LockType.NONE -> false
        LockType.PIN -> prefs.hasPin
        else -> true
    }
    @Composable
    private fun LockScreen(listViewModel: DocumentListViewModel) {
        var usePin by androidx.compose.runtime.remember { mutableStateOf(prefs.lockType == LockType.PIN) }
        var lockoutSeconds by androidx.compose.runtime.remember { mutableStateOf(listViewModel.pinLockoutSeconds()) }
        androidx.compose.runtime.LaunchedEffect(lockoutSeconds) {
            if (lockoutSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                lockoutSeconds = listViewModel.pinLockoutSeconds()
            }
        }
        val isArabic = resources.configuration.locales[0].language == "ar"
        if (usePin && prefs.hasPin) {
            PinLockScreen(
                title = if (isArabic) "التطبيق مقفل" else "DocScan Pro Locked",
                subtitle = if (lockoutSeconds > 0) {
                    if (isArabic) "محاولات كثيرة خاطئة. حاول بعد $lockoutSeconds ثانية" else "Too many attempts. Try again in $lockoutSeconds s"
                } else {
                    if (isArabic) "أدخل رمز PIN المكوّن من 4 أرقام" else "Enter 4-digit PIN to access documents"
                },
                onPinEntered = { entered ->
                    val ok = listViewModel.verifyPin(entered)
                    lockoutSeconds = listViewModel.pinLockoutSeconds()
                    ok
                },
                onSuccess = { mainViewModel.setAuthenticated(true) }
            )
            return
        }
        val canAuth = canAuthenticate()
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = if (isArabic) "مقفل" else "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
            if (canAuth) {
                androidx.compose.material3.Button(onClick = { showBiometricPrompt() }) {
                    androidx.compose.material3.Text(if (isArabic) "فتح القفل" else "Unlock")
                }
            } else {
                androidx.compose.material3.Text(
                    if (isArabic) "لا يمكن التحقق من هويتك: فعّل قفل الشاشة أو البصمة في إعدادات الجهاز."
                    else "Authentication is unavailable: set up a screen lock or biometrics in device settings.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                androidx.compose.material3.TextButton(onClick = {
                    runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
                }) { androidx.compose.material3.Text(if (isArabic) "فتح الإعدادات" else "Open settings") }
            }
            if (prefs.hasPin) {
                androidx.compose.material3.TextButton(onClick = { usePin = true }) {
                    androidx.compose.material3.Text(if (isArabic) "استخدام رمز PIN" else "Use PIN")
                }
            }
        }
    }
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    override fun onStart() {
        super.onStart()
        mainViewModel.onAppForegrounded(isLockEnabled())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!isLockEnabled())
    }
    override fun onResume() {
        super.onResume()
        mainViewModel.onResumeCheckInstallPermission()
        if (prefs.lockType == LockType.BIOMETRIC && !mainViewModel.isAuthenticated.value) {
            showBiometricPrompt()
        }
    }
    private fun showBiometricPrompt() {
        if (mainViewModel.isPromptShowing) return
        if (!canAuthenticate()) return
        mainViewModel.setPromptShowing(true)
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    mainViewModel.setPromptShowing(false)
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    mainViewModel.setAuthenticated(true)
                    mainViewModel.setPromptShowing(false)
                }
                override fun onAuthenticationFailed() { super.onAuthenticationFailed() }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("DocScan Pro Lock")
            .setSubtitle("Authenticate to access your documents")
            .setAllowedAuthenticators(authenticators())
            .build()
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            mainViewModel.setPromptShowing(false)
        }
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) mainViewModel.onAppBackgrounded()
    }
}
@Composable
fun DocScanApp(
    cameraViewModel: CameraViewModel,
    listViewModel: DocumentListViewModel,
    editViewModel: EditSessionViewModel,
    mainViewModel: MainViewModel
) {
    val navController = rememberNavController()
    val listUiState by listViewModel.uiState.collectAsState()
    val editUiState by editViewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val incoming by mainViewModel.incomingImport.collectAsState()
    val isArabicApp = context.resources.configuration.locales[0].language == "ar"
    fun tApp(en: String, ar: String) = if (isArabicApp) ar else en

    val fileSaver = androidx.compose.runtime.saveable.Saver<File?, String>(
        save = { it?.absolutePath ?: "" },
        restore = { p -> p.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() } }
    )
    var pdfToChoose by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = fileSaver) { mutableStateOf<File?>(null) }
    var pdfReading by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = fileSaver) { mutableStateOf<File?>(null) }
    var showReaderTools by remember { mutableStateOf(false) }
    var signBusy by remember { mutableStateOf(false) }
    var preparingPdf by remember { mutableStateOf(false) }
    var passwordPromptFor by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = fileSaver) { mutableStateOf<File?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }

    fun preparePdf(source: File, password: String? = null) {
        preparingPdf = true
        coroutineScope.launch {
            when (val result = PdfCompat.prepare(context, source, password)) {
                is PdfCompat.Result.Ready -> {
                    passwordPromptFor = null
                    wrongPassword = false
                    pdfToChoose = result.file
                }
                is PdfCompat.Result.NeedsPassword -> {
                    wrongPassword = result.wrongPassword
                    passwordPromptFor = source
                }
                is PdfCompat.Result.Unreadable -> {
                    passwordPromptFor = null
                    android.widget.Toast.makeText(
                        context,
                        tApp("This file is not a readable PDF", "هذا الملف ليس PDF قابلاً للقراءة"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            preparingPdf = false
        }
    }

    fun openPdfInEditor(file: File) {
        pdfToChoose = null
        pdfReading = null
        showReaderTools = false
        cameraViewModel.setImportedPdfPendingEdit(Uri.fromFile(file))
        navController.navigate(Screen.EditSession.createRoute("IMPORT", 0L)) { launchSingleTop = true }
    }

    fun signPdfPage(file: File, pageIndex: Int) {
        if (signBusy) return
        signBusy = true
        coroutineScope.launch {
            try {
                val docId = ExternalPdfImporter.importAsDocument(context, file)
                if (docId == null) {
                    android.widget.Toast.makeText(
                        context,
                        tApp("This PDF cannot be signed (protected or damaged)", "لا يمكن توقيع هذا الملف (محمي أو تالف)"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                editViewModel.loadDocument(docId)
                val loaded = withTimeoutOrNull(15_000L) {
                    editViewModel.uiState.first { it.activeDocument?.id == docId && it.activePages.isNotEmpty() }
                }
                showReaderTools = false
                pdfReading = null
                navController.navigate(Screen.DocumentViewer.createRoute(docId)) { launchSingleTop = true }
                if (loaded != null) {
                    val idx = pageIndex.coerceIn(0, loaded.activePages.lastIndex)
                    editViewModel.selectPageIndex(idx)
                    navController.navigate(Screen.Annotate.createRoute(docId, loaded.activePages[idx].id))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, tApp("Could not prepare the PDF", "تعذر تجهيز ملف PDF"), android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                signBusy = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(incoming) {
        when (val item = mainViewModel.consumeIncoming()) {
            is MainViewModel.IncomingImport.Pdf -> {
                val local = com.example.engine.pdf.PdfEngine.copyUriToLocalPdf(context, item.uri)
                if (local != null) {
                    pdfReading = null
                    preparePdf(local)
                } else {
                    android.widget.Toast.makeText(context, tApp("Could not open the PDF", "تعذر فتح ملف PDF"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            is MainViewModel.IncomingImport.Images -> {
                cameraViewModel.setImportedUrisPendingEdit(item.uris)
                navController.navigate(Screen.EditSession.createRoute("IMPORT", 0L)) { launchSingleTop = true }
            }
            MainViewModel.IncomingImport.DefaultAppCheck -> {
                val st = DefaultPdfApp.status(context)
                android.widget.Toast.makeText(
                    context,
                    if (st == DefaultPdfApp.Status.ThisApp) tApp("MS Scanner is now your default PDF app ✓", "أصبح MS Scanner التطبيق الافتراضي لملفات PDF ✓")
                    else tApp("Opened with MS Scanner. Choose \"Always\" to make it the default.", "تم الفتح بـ MS Scanner. اختر «دائماً» لجعله الافتراضي."),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                context.findActivity()?.let { if (!it.isTaskRoot) it.finish() }
            }
            null -> Unit
        }
    }
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                listViewModel = listViewModel,
                onPagesCaptured = { pages -> cameraViewModel.setPagesPendingEdit(pages) },
                onImportedUris = { uris -> cameraViewModel.setImportedUrisPendingEdit(uris) },
                onOpenPdfFile = { file -> preparePdf(file) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDocument = { docId -> navController.navigate(Screen.DocumentViewer.createRoute(docId)) },
                onNavigateToScan = { modeStr -> navController.navigate(Screen.CameraScan.createRoute(mode = modeStr)) },
                onNavigateToEditSession = { sourceType, docId -> navController.navigate(Screen.EditSession.createRoute(sourceType, docId)) }
            )
        }
        composable(
            route = Screen.CameraScan.route,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "DOCUMENT" },
                navArgument("docId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("replacePageId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { backStackEntry ->
            val modeStr = backStackEntry.arguments?.getString("mode") ?: "DOCUMENT"
            val cameraMode = when (modeStr.uppercase()) {
                "ID_CARD" -> ScanCameraMode.ID_CARD
                "PASSPORT" -> ScanCameraMode.PASSPORT
                "BATCH", "MULTI", "MULTI_PAGE" -> ScanCameraMode.BATCH
                else -> ScanCameraMode.DOCUMENT
            }
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val replacePageId = backStackEntry.arguments?.getLong("replacePageId") ?: 0L
            val isArabicUi = context.resources.configuration.locales[0].language == "ar"
            val deliverPages: (List<Pair<String, String>>, String) -> Unit = { pages, source ->
                val target = cameraViewModel.consumeCaptureTarget()
                when {
                    target is CaptureTarget.Replace -> {
                        pages.firstOrNull()?.let { cameraViewModel.replacePendingPage(target.index, it) }
                        if (pages.size > 1) cameraViewModel.appendPendingPages(pages.drop(1))
                        navController.popBackStack()
                    }
                    target is CaptureTarget.Append -> {
                        cameraViewModel.appendPendingPages(pages)
                        navController.popBackStack()
                    }
                    replacePageId > 0L -> {
                        pages.firstOrNull()?.let { editViewModel.replacePage(replacePageId, it.first, it.second) }
                        navController.popBackStack()
                    }
                    else -> {
                        cameraViewModel.setPagesPendingEdit(pages)
                        navController.navigate(Screen.EditSession.createRoute(source, docId)) {
                            popUpTo(Screen.CameraScan.route) { inclusive = true }
                        }
                    }
                }
            }
                       val appPrefs = remember { AppPreferences(context) }
            // Unified Scan Document entry: CameraScanScreen's own mode selector (Document/Batch/
            // ID Card/Passport) must always be reachable before any external scanner engine starts.
            val googleEligible = false
            val pageLimit: Int? = remember(backStackEntry.id) {
                when {
                    replacePageId > 0L || cameraViewModel.peekCaptureTarget() is CaptureTarget.Replace -> 1
                    cameraMode == ScanCameraMode.ID_CARD || cameraMode == ScanCameraMode.PASSPORT -> 2
                    else -> null
                }
            }
            var scannerStage by androidx.compose.runtime.saveable.rememberSaveable(backStackEntry.id) {
                mutableStateOf(if (googleEligible) "PREPARING" else "CAMERA")
            }
            var preparingProgress by remember { mutableStateOf<Int?>(null) }
            val scannerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                mainViewModel.endExternalFlow()
                val uris = GoogleDocumentScanner.pageUris(result.resultCode, result.data)
                if (uris.isEmpty()) {
                    cameraViewModel.consumeCaptureTarget()
                    navController.popBackStack()
                } else {
                    scannerStage = "PROCESSING"
                    cameraViewModel.processScannerResult(uris)
                }
            }
            androidx.compose.runtime.LaunchedEffect(backStackEntry.id) {
                if (scannerStage != "PREPARING") return@LaunchedEffect
                cameraViewModel.discardStaleScannerPages()
                val activity = context.findActivity()
                if (activity == null) {
                    scannerStage = "CAMERA"
                    return@LaunchedEffect
                }
                GoogleDocumentScanner.start(
                    activity = activity,
                    pageLimit = pageLimit,
                    onPreparing = { preparingProgress = it },
                    onIntent = { sender ->
                        try {
                            mainViewModel.beginExternalFlow()
                            scannerStage = "WAITING"
                            scannerLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                        } catch (e: Exception) {
                            mainViewModel.endExternalFlow()
                            scannerStage = "CAMERA"
                        }
                    },
                    onError = { e ->
                        e.printStackTrace()
                        android.widget.Toast.makeText(
                            context,
                            (if (isArabicUi) e.userMessageAr else e.userMessageEn) +
                                (if (isArabicUi) " — تم فتح الكاميرا المدمجة" else " — using the built-in camera"),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        scannerStage = "CAMERA"
                    }
                )
            }
            val scannedPages by cameraViewModel.scannerPages.collectAsState()
            androidx.compose.runtime.LaunchedEffect(scannedPages) {
                if (scannerStage != "PROCESSING" || scannedPages == null) return@LaunchedEffect
                val pages = cameraViewModel.consumeScannerPages() ?: return@LaunchedEffect
                if (pages.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        if (isArabicUi) "تعذر قراءة الصفحات الممسوحة" else "Could not read the scanned pages",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    cameraViewModel.consumeCaptureTarget()
                    navController.popBackStack()
                } else {
                    deliverPages(pages, "SCANNER")
                }
            }
            if (scannerStage != "CAMERA") {
                androidx.activity.compose.BackHandler(enabled = scannerStage == "PROCESSING") { }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                        val label = when (scannerStage) {
                            "PROCESSING" -> if (isArabicUi) "جاري تجهيز الصفحات…" else "Preparing pages…"
                            "PREPARING" -> preparingProgress?.let { p ->
                                if (isArabicUi) "جاري تنزيل ماسح Google… $p%" else "Downloading the Google scanner… $p%"
                            }
                            else -> null
                        }
                        if (label != null) {
                            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                            androidx.compose.material3.Text(label)
                        }
                    }
                }
                return@composable
            }
            // FIX (issue 1 — Normal Document must be the actual default): previously `useSavedMode` reopened
            // whichever ScanCameraMode (DOCUMENT/BATCH/ID_CARD/PASSPORT) the user used last, even when the
            // entry point was the main "Scan Document" action. The entry mode (`cameraMode`, derived from the
            // nav route argument) is now ALWAYS honoured, so "Scan Document" always starts on Normal Document
            // as requested, regardless of what mode was used previously in the same app session.
            val useSavedMode = false
            CameraScanScreen(
                initialMode = cameraMode,
                docId = docId,
                replacePageId = replacePageId,
                useSavedMode = useSavedMode,
                onNavigateBack = {
                    cameraViewModel.consumeCaptureTarget()
                    navController.popBackStack()
                },
                onDocumentCaptured = { pages -> deliverPages(pages, "CAMERA") },
                onIdCardCaptured = { _, _ -> },
                onPassportCaptured = { _, _ -> }
            )
        }
        composable(
            route = Screen.DocumentViewer.route,
            arguments = listOf(navArgument("docId") { type = NavType.LongType })
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            DocumentViewerScreen(
                docId = docId,
                viewModel = editViewModel,
                onImportedUris = { uris -> cameraViewModel.setImportedUrisPendingEdit(uris) },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScan = { dId, replacePageId -> navController.navigate(Screen.CameraScan.createRoute(mode = "DOCUMENT", docId = dId, replacePageId = replacePageId)) },
                onNavigateToCrop = { dId, pageId -> navController.navigate(Screen.CropEditor.createRoute(dId, pageId)) },
                onNavigateToOcr = { dId, pageId -> navController.navigate(Screen.Ocr.createRoute(dId, pageId)) },
                onNavigateToAnnotate = { dId, pageId -> navController.navigate(Screen.Annotate.createRoute(dId, pageId)) },
                onNavigateToEditSession = { sourceType, dId -> navController.navigate(Screen.EditSession.createRoute(sourceType, dId)) }
            )
        }
        composable(
            route = Screen.CropEditor.route,
            arguments = listOf(
                navArgument("docId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
            val activePage = editUiState.activePages.find { it.id == pageId }
            val imagePath = activePage?.rawImagePath?.ifBlank { null } ?: activePage?.processedImagePath ?: ""
            if (activePage == null || imagePath.isBlank()) {
                androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                var resultDelivered by androidx.compose.runtime.remember(pageId) { mutableStateOf(false) }
                DocumentCropEditorScreen(
                    imagePath = imagePath,
                    initialQuad = DocumentQuad.fromJsonOrNull(activePage.cropQuadJson),
                    initialRotation = activePage.rotationDegrees,
                    initialFilter = runCatching { FilterType.valueOf(activePage.filterType) }.getOrNull()
                        ?.takeIf { it != FilterType.ORIGINAL },
                    onCropResult = { r ->
                        resultDelivered = true
                        editViewModel.updateActivePageCrop(pageId, r)
                    },
                    onCropped = { newPath ->
                        if (!resultDelivered) editViewModel.updateActivePageProcessedImage(newPath)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() }
                )
            }
        }
        composable(
            route = Screen.Ocr.route,
            arguments = listOf(
                navArgument("docId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
            OcrScreen(docId = docId, pageId = pageId, viewModel = editViewModel, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.Annotate.route,
            arguments = listOf(
                navArgument("docId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
            AnnotationScreen(docId = docId, pageId = pageId, viewModel = editViewModel, onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            val updateCheckState by mainViewModel.updateCheckState.collectAsState()
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                SettingsScreen(
                    viewModel = listViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    modifier = Modifier.weight(1f),
                    updateCheckState = updateCheckState,
                    onCheckForUpdates = { mainViewModel.checkForUpdates(manual = true) }
                )
                DefaultPdfAppBanner()
            }
        }
        composable(
            route = Screen.EditSession.route,
            arguments = listOf(
                navArgument("sourceType") { type = NavType.StringType },
                navArgument("docId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sourceType = backStackEntry.arguments?.getString("sourceType") ?: "CAMERA"
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val cameraUiState by cameraViewModel.uiState.collectAsState()
            val listUiState by listViewModel.uiState.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                cameraViewModel.events.collect { event ->
                    if (event is com.example.ui.util.UiEvent.Error) {
                        android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val targetFolder = listUiState.selectedFolder.takeUnless { it.isBlank() || it.equals("ALL", ignoreCase = true) } ?: "Default"
            EditSessionScreen(
                editViewModel = editViewModel,
                pagesPendingEdit = cameraUiState.pagesPendingEdit,
                cameraIsLoading = cameraUiState.isLoading,
                onUpdatePendingPage = { idx, path -> cameraViewModel.updatePendingPageProcessedImage(idx, path) },
                onRotatePendingPage = { idx, cw -> cameraViewModel.rotatePendingPage(idx, cw) },
                onCommitPending = { dId, onDone -> cameraViewModel.commitPendingPagesToDocument(dId, onDone) },
                onImportPages = { title, folder, onDone -> cameraViewModel.importPagesAsDocument(title, folder, onDone) },
                onClearPending = { cameraViewModel.clearPendingPages(deleteFiles = true) },
                selectedFolder = targetFolder,
                sourceType = sourceType,
                docId = docId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFinish = { newDocId ->
                    if (sourceType != "EXISTING") editViewModel.requestShareSheet(newDocId)
                    navController.navigate(Screen.DocumentViewer.createRoute(newDocId)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToAnnotate = { dId, pageId -> navController.navigate(Screen.Annotate.createRoute(dId, pageId)) },
                pendingEdits = cameraUiState.pageEdits,
                cameraIsProcessing = cameraUiState.isProcessingEdit,
                cameraIsSaving = cameraUiState.isSaving,
                cameraIsBackgroundProcessing = cameraUiState.isBackgroundProcessing,
                onDeletePendingPage = { idx -> cameraViewModel.deletePendingPage(idx) },
                onMovePendingPage = { from, to -> cameraViewModel.movePendingPage(from, to) },
                onApplyPendingEdits = { indices, changeFilter, filter, brightness, contrast ->
                    cameraViewModel.applyEditsToPendingPages(indices, changeFilter, filter, brightness, contrast)
                },
                onCropPendingPage = { idx, result -> cameraViewModel.updatePendingPageCrop(idx, result) },
                onRetakePendingPage = { idx ->
                    cameraViewModel.setCaptureTarget(CaptureTarget.Replace(idx))
                    navController.navigate(Screen.CameraScan.createRoute(mode = "DOCUMENT"))
                },
                onAddPendingPage = {
                    cameraViewModel.setCaptureTarget(CaptureTarget.Append)
                    navController.navigate(Screen.CameraScan.createRoute(mode = "BATCH"))
                },
                pageStatuses = cameraUiState.pageStatuses,
                onRetryDetection = { idx -> cameraViewModel.retryDetection(idx) },
                onAcceptPageAsIs = { idx -> cameraViewModel.acceptPageAsIs(idx) },
                onPageReviewed = { idx -> cameraViewModel.markPageReviewed(idx) },
                onSplitPendingPage = { idx, rightFirst -> cameraViewModel.splitPendingPage(idx, rightFirst) }
            )
        }
    }

    if (preparingPdf) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                    androidx.compose.material3.Text(tApp("Opening PDF…", "جاري فتح ملف PDF…"))
                }
            }
        }
    }
    passwordPromptFor?.let { file ->
        PdfPasswordDialog(
            fileName = file.nameWithoutExtension.replace(Regex("^\\d{10,}_"), ""),
            wrongPassword = wrongPassword,
            onDismiss = { passwordPromptFor = null; wrongPassword = false },
            onSubmit = { pwd -> preparePdf(file, pwd) }
        )
    }
    pdfToChoose?.let { file ->
        OpenPdfActionDialog(
            pdfFile = file,
            onDismiss = { pdfToChoose = null },
            onBrowseOnly = {
                pdfToChoose = null
                pdfReading = file
            },
            onEditInStudio = { openPdfInEditor(file) }
        )
    }
    pdfReading?.let { file ->
        PdfViewerOverlay(
            pdfFile = file,
            onDismiss = {
                pdfReading = null
                showReaderTools = false
            },
            onEditClick = { showReaderTools = true }
        )
        if (showReaderTools) {
            PdfReaderToolsSheet(
                pdfFile = file,
                isBusy = signBusy,
                onDismiss = { showReaderTools = false },
                onSign = { pageIndex -> signPdfPage(file, pageIndex) },
                onEdit = { openPdfInEditor(file) }
            )
        }
    }
}
