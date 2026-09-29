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
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    /** elapsedRealtime when the app went to background; 0 = never (cold start: always locked). */
    private var backgroundedAt = 0L
    /**
     * True while an activity started BY the app for a result is in front (Google document scanner).
     * Without this, a multi-page scan longer than the lock grace period re-locked the app on return:
     * the navigation tree left the composition and the scanner result (the pages) was lost.
     */
    private var externalFlowActive = false
    fun setAuthenticated(value: Boolean) {
        _isAuthenticated.value = value
    }
    fun setPromptShowing(value: Boolean) {
        _isPromptShowing = value
    }
    fun onAppBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }
    fun beginExternalFlow() {
        externalFlowActive = true
    }
    fun endExternalFlow() {
        externalFlowActive = false
        backgroundedAt = SystemClock.elapsedRealtime()
    }
    /**
     * Re-locks when the app was away longer than the grace period (short trips to the share sheet,
     * picker, system camera or print dialog do not ask for authentication again).
     */
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
    /** PDF / image opened or shared from another app (the manifest declares the PDF VIEW / SEND filters). */
    sealed class IncomingImport {
        data class Pdf(val uri: Uri) : IncomingImport()
        data class Images(val uris: List<Uri>) : IncomingImport()
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
    /** Automatic check at most once a day; [manual] always checks and ignores "skip this version". */
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
    /**
     * Install button: grants "install unknown apps" first when needed, then installs IN PLACE through a
     * PackageInstaller session (same package + same key = update over the existing app, data kept).
     */
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
                // Fallback: classic installer screen.
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
    /** Back from the "install unknown apps" settings screen. */
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
        // Initial state: if lock is NOT effectively enabled, we are already "authenticated"
        if (!isLockEnabled()) {
            mainViewModel.setAuthenticated(true)
        }
        // Single update system (GitHub Releases): check on launch (at most once a day) + every 12 h in the
        // background with a notification, + "Check for updates" in Settings.
        mainViewModel.checkForUpdates(manual = intent?.getBooleanExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATE, false) == true)
        UpdateCheckWorker.schedule(applicationContext)
        // Previously the app was registered as a PDF handler but ignored the opened / shared file.
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
                        // Update dialog only after unlock (never over the lock screen).
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result read by the worker */ }
    /** Android 13+: asked once so the background update check can notify; everything works without it. */
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
        val type = intent.type.orEmpty()
        fun typeOf(u: Uri) = runCatching { contentResolver.getType(u) }.getOrNull() ?: type
        val pdf = uris.firstOrNull { typeOf(it) == "application/pdf" || it.toString().endsWith(".pdf", true) }
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
    /** PIN lock without a PIN could never be unlocked; it is treated as "no lock" (nothing to protect with). */
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
        // Biometric / device credential lock: the app NEVER unlocks itself when authentication is unavailable.
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
    /** API 28-29 do not support BIOMETRIC_STRONG | DEVICE_CREDENTIAL (the old combination made the lock open itself). */
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
        // Hide document content in the Recents screen while a lock is enabled (API 33+).
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
        // Never unlock because authentication is unavailable: the lock screen explains what to do instead.
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
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
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
        // Rotation / language change is not "leaving the app".
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
    androidx.compose.runtime.LaunchedEffect(incoming) {
        when (val item = mainViewModel.consumeIncoming()) {
            is MainViewModel.IncomingImport.Pdf -> {
                // Copied first: the grant of an opened/shared URI does not outlive the calling task.
                val local = com.example.engine.pdf.PdfEngine.copyUriToLocalPdf(context, item.uri)
                if (local != null) {
                    cameraViewModel.setImportedPdfPendingEdit(Uri.fromFile(local))
                    navController.navigate(Screen.EditSession.createRoute("IMPORT", 0L)) { launchSingleTop = true }
                } else {
                    android.widget.Toast.makeText(context, "Could not open the PDF", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            is MainViewModel.IncomingImport.Images -> {
                cameraViewModel.setImportedUrisPendingEdit(item.uris)
                navController.navigate(Screen.EditSession.createRoute("IMPORT", 0L)) { launchSingleTop = true }
            }
            null -> Unit
        }
    }
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // Home Screen
        composable(Screen.Home.route) {
            HomeScreen(
                listViewModel = listViewModel,
                onPagesCaptured = { pages -> cameraViewModel.setPagesPendingEdit(pages) },
                onImportedUris = { uris -> cameraViewModel.setImportedUrisPendingEdit(uris) },
                // PDF pages enter the SAME session / review / save flow as camera and gallery pages.
                onOpenPdfFile = { file ->
                    cameraViewModel.setImportedPdfPendingEdit(Uri.fromFile(file))
                    navController.navigate(Screen.EditSession.createRoute("IMPORT", 0L))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToDocument = { docId ->
                    navController.navigate(Screen.DocumentViewer.createRoute(docId))
                },
                onNavigateToScan = { modeStr ->
                    navController.navigate(Screen.CameraScan.createRoute(mode = modeStr))
                },
                onNavigateToEditSession = { sourceType, docId ->
                    navController.navigate(Screen.EditSession.createRoute(sourceType, docId))
                }
            )
        }
        // Scan entry: Google Document Scanner first, built-in camera only as a real fallback.
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

            /**
             * Single delivery point for captured pages, whatever the capture engine (Google scanner or
             * built-in camera): retake / add page / replace saved page / new session.
             */
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
                            // The scan entry is removed from the back stack: Back from review never re-opens it.
                            popUpTo(Screen.CameraScan.route) { inclusive = true }
                        }
                    }
                }
            }

            // ---- Engine selection (decided ONCE per entry, before anything is shown) ----
            // Every mode uses Google when the device supports it (ID card / passport included: pages are
            // limited to 2). The previous code sent ID card / passport to the built-in camera always, so
            // the engine depended on the document type.
            val appPrefs = remember { AppPreferences(context) }
            val googleEligible = remember(backStackEntry.id) {
                appPrefs.scanEngine == AppPreferences.SCAN_ENGINE_GOOGLE && GoogleDocumentScanner.isSupported(context)
            }
            val pageLimit: Int? = remember(backStackEntry.id) {
                when {
                    replacePageId > 0L || cameraViewModel.peekCaptureTarget() is CaptureTarget.Replace -> 1
                    cameraMode == ScanCameraMode.ID_CARD || cameraMode == ScanCameraMode.PASSPORT -> 2
                    else -> null
                }
            }
            // PREPARING (availability check / module download) -> WAITING (Google UI open) -> PROCESSING -> delivered.
            // CAMERA = built-in camera (setting, unsupported device, or a REAL failure of the Google scanner).
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
                    // Cancelled in the Google screen: back to where the user came from, nothing changed.
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
                // The built-in camera is NEVER composed before the Google scanner: only this neutral screen.
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

            // ---- Built-in camera (fallback) ----
            // Normal "Scan" entry opens in the mode the user chose last time; explicit modes
            // (retake / add page / replace / ID / passport shortcuts) are kept as requested.
            val useSavedMode = remember(backStackEntry.id) {
                modeStr.equals("DOCUMENT", ignoreCase = true) && replacePageId == 0L && cameraViewModel.peekCaptureTarget() == null
            }
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
                onIdCardCaptured = { _, _ ->
                    // ID card / passport pages are delivered through onDocumentCaptured.
                },
                onPassportCaptured = { _, _ ->
                    // Delivered through onDocumentCaptured.
                }
            )
        }
        // Document Viewer Screen
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
                onNavigateToScan = { dId, replacePageId ->
                    navController.navigate(Screen.CameraScan.createRoute(mode = "DOCUMENT", docId = dId, replacePageId = replacePageId))
                },
                onNavigateToCrop = { dId, pageId ->
                    navController.navigate(Screen.CropEditor.createRoute(dId, pageId))
                },
                onNavigateToOcr = { dId, pageId ->
                    navController.navigate(Screen.Ocr.createRoute(dId, pageId))
                },
                onNavigateToAnnotate = { dId, pageId ->
                    navController.navigate(Screen.Annotate.createRoute(dId, pageId))
                },
                onNavigateToEditSession = { sourceType, dId ->
                    navController.navigate(Screen.EditSession.createRoute(sourceType, dId))
                }
            )
        }
        // Crop Editor Screen
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
                    // Opens with the stored crop/rotation/filter instead of re-detecting and losing edits.
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
        // OCR Screen
        composable(
            route = Screen.Ocr.route,
            arguments = listOf(
                navArgument("docId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
            OcrScreen(
                docId = docId,
                pageId = pageId,
                viewModel = editViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        // Annotation Screen
        composable(
            route = Screen.Annotate.route,
            arguments = listOf(
                navArgument("docId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
            AnnotationScreen(
                docId = docId,
                pageId = pageId,
                viewModel = editViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        // Settings Screen
        composable(Screen.Settings.route) {
            val updateCheckState by mainViewModel.updateCheckState.collectAsState()
            SettingsScreen(
                viewModel = listViewModel,
                onNavigateBack = { navController.popBackStack() },
                updateCheckState = updateCheckState,
                onCheckForUpdates = { mainViewModel.checkForUpdates(manual = true) }
            )
        }
        // Edit Session Screen (review -> save)
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
            // "ALL" is a list filter, not a folder: new documents must never be saved into it.
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
                    // New scan saved -> the document opens directly on the Save & Share options.
                    if (sourceType != "EXISTING") editViewModel.requestShareSheet(newDocId)
                    navController.navigate(Screen.DocumentViewer.createRoute(newDocId)) {
                        // Review is removed: Back from the saved document returns Home, never to a stale review.
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToAnnotate = { dId, pageId ->
                    navController.navigate(Screen.Annotate.createRoute(dId, pageId))
                },
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
}
