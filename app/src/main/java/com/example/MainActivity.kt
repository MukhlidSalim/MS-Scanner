package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import com.example.ui.viewmodel.DocumentListViewModel
import com.example.ui.viewmodel.EditSessionViewModel
import com.example.data.model.LockType

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.data.repository.AppPreferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider


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

class MainViewModel : ViewModel() {
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var _isPromptShowing = false
    val isPromptShowing: Boolean get() = _isPromptShowing

    private val _updateInfo = mutableStateOf<com.example.update.UpdateManager.VersionInfo?>(null)
    val updateInfo: androidx.compose.runtime.State<com.example.update.UpdateManager.VersionInfo?> = _updateInfo

    fun setUpdateInfo(info: com.example.update.UpdateManager.VersionInfo?) {
        _updateInfo.value = info
    }

    fun setAuthenticated(value: Boolean) {
        _isAuthenticated.value = value
    }

    fun setPromptShowing(value: Boolean) {
        _isPromptShowing = value
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

        // Initial state: if lock is NOT enabled, we are already "authenticated"
        if (prefs.lockType == LockType.NONE) {
            mainViewModel.setAuthenticated(true)
        }

        val database = DocScanDatabase.getInstance(applicationContext)
        val repository = DocumentRepository(applicationContext, database.documentDao())

        lifecycleScope.launch {
            val info = com.example.update.UpdateManager.checkForUpdates(this@MainActivity)
            mainViewModel.setUpdateInfo(info)
        }

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
            val updateInfo by mainViewModel.updateInfo

            DocScanTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (updateInfo != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { mainViewModel.setUpdateInfo(null) },
                            title = { androidx.compose.material3.Text("Update Available") },
                            text = { androidx.compose.material3.Text("A new version of the app is available. Would you like to update?") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        lifecycleScope.launch {
                                            com.example.update.UpdateManager.downloadAndInstall(this@MainActivity, updateInfo!!.downloadUrl)
                                            mainViewModel.setUpdateInfo(null)
                                        }
                                    }
                                ) { androidx.compose.material3.Text("Update") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { mainViewModel.setUpdateInfo(null) }) {
                                    androidx.compose.material3.Text("Cancel")
                                }
                            }
                        )
                    }

                    if (isAuthenticated) {
                        DocScanApp(
                            cameraViewModel = cameraViewModel,
                            listViewModel = listViewModel,
                            editViewModel = editViewModel,
                            mainViewModel = mainViewModel
                        )
                    } else {
                        if (prefs.lockType == LockType.PIN) {
                            PinLockScreen(
                                title = "DocScan Pro Locked",
                                subtitle = "Enter 4-digit PIN to access documents",
                                onPinEntered = { entered -> listViewModel.verifyPin(entered) },
                                onSuccess = { mainViewModel.setAuthenticated(true) }
                            )
                        } else {
                            // Biometric or fallback
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.lockType == LockType.BIOMETRIC && !mainViewModel.isAuthenticated.value) {
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        if (mainViewModel.isPromptShowing) return

        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        
        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> { /* Proceed */ }
            else -> {
                mainViewModel.setAuthenticated(true)
                return
            }
        }

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
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onStop() {
        super.onStop()
        if (prefs.lockType != LockType.NONE) {
            mainViewModel.setAuthenticated(false)
            mainViewModel.setPromptShowing(false)
        }
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

        // Camera Scan Screen
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
                else -> ScanCameraMode.DOCUMENT
            }
            val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
            val replacePageId = backStackEntry.arguments?.getLong("replacePageId") ?: 0L

            CameraScanScreen(
                initialMode = cameraMode,
                docId = docId,
                replacePageId = replacePageId,
                onNavigateBack = { navController.popBackStack() },
                onDocumentCaptured = { pages ->
                    if (replacePageId > 0L) {
                        val firstPage = pages.firstOrNull()
                        if (firstPage != null) {
                            editViewModel.replacePage(replacePageId, firstPage.first, firstPage.second)
                        }
                        navController.popBackStack()
                    } else {
                        cameraViewModel.setPagesPendingEdit(pages)
                        navController.navigate(Screen.EditSession.createRoute("CAMERA", docId)) {
                            if (docId == 0L) popUpTo(Screen.Home.route)
                            else popUpTo(Screen.DocumentViewer.createRoute(docId))
                        }
                    }
                },
                onIdCardCaptured = { front, back ->
                    // Logic moved internally to EditSession or component
                },
                onPassportCaptured = { front, back ->
                    // Logic moved internally
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
            val imagePath = activePage?.rawImagePath ?: activePage?.processedImagePath ?: ""

            DocumentCropEditorScreen(
                imagePath = imagePath,
                onCropped = { newPath ->
                    editViewModel.updateActivePageProcessedImage(newPath)
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
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
            SettingsScreen(
                viewModel = listViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Edit Session Screen
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

            EditSessionScreen(
                editViewModel = editViewModel,
                pagesPendingEdit = cameraUiState.pagesPendingEdit,
                cameraIsLoading = cameraUiState.isLoading,
                onUpdatePendingPage = { idx, path -> cameraViewModel.updatePendingPageProcessedImage(idx, path) },
                onRotatePendingPage = { idx, cw -> cameraViewModel.rotatePendingPage(idx, cw) },
                onCommitPending = { dId, onDone -> cameraViewModel.commitPendingPagesToDocument(dId, onDone) },
                onImportPages = { title, folder, onDone -> cameraViewModel.importPagesAsDocument(title, folder, onDone) },
                onClearPending = { cameraViewModel.clearPendingPages(deleteFiles = true) },
                selectedFolder = listUiState.selectedFolder,
                sourceType = sourceType,
                docId = docId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFinish = { newDocId ->
                    navController.navigate(Screen.DocumentViewer.createRoute(newDocId)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onNavigateToAnnotate = { dId, pageId ->
                    navController.navigate(Screen.Annotate.createRoute(dId, pageId))
                }
            )
        }
    }
}
