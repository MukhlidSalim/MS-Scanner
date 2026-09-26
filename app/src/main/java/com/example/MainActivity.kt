package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
import com.example.ui.navigation.Screen
import com.example.ui.screens.annotate.AnnotationScreen
import com.example.ui.screens.camera.CameraScanScreen
import com.example.ui.screens.camera.ScanCameraMode
import com.example.ui.screens.editor.DocumentCropEditorScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.idcard.IdCardMergerScreen
import com.example.ui.screens.ocr.OcrScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.viewer.DocumentViewerScreen
import com.example.ui.theme.DocScanTheme
import com.example.ui.viewmodel.DocumentViewModel

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = DocScanDatabase.getInstance(applicationContext)
        val repository = DocumentRepository(applicationContext, database.documentDao())

        setContent {
            val docViewModel = remember { DocumentViewModel(applicationContext, repository) }
            val docUiState by docViewModel.uiState.collectAsState()

            val isDarkTheme = when (docUiState.themeMode) {
                "Light" -> false
                "Dark" -> true
                else -> isSystemInDarkTheme()
            }

            DocScanTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DocScanApp(
                        docViewModel = docViewModel
                    )
                }
            }
        }
    }
}

@Composable
fun DocScanApp(
    docViewModel: DocumentViewModel
) {
    val navController = rememberNavController()
    val docUiState by docViewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                docViewModel.lockApp()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (docUiState.isAppLocked) {
        PinLockScreen(
            title = "DocScan Pro Locked",
            subtitle = "Enter 4-digit PIN to access documents",
            onPinEntered = { entered -> docViewModel.verifyPin(entered) },
            onSuccess = { /* unmasked by verifyPin */ }
        )
    } else {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
            // Home Screen
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = docViewModel,
                    onNavigateToScan = { modeStr ->
                        navController.navigate(Screen.CameraScan.createRoute(mode = modeStr))
                    },
                    onNavigateToDocument = { docId ->
                        navController.navigate(Screen.DocumentViewer.createRoute(docId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            // In-App Advanced Camera Scan Screen
            composable(
                route = Screen.CameraScan.route,
                arguments = listOf(
                    navArgument("mode") {
                        type = NavType.StringType
                        defaultValue = "DOCUMENT"
                    },
                    navArgument("docId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("replacePageId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { backStackEntry ->
                val modeStr = backStackEntry.arguments?.getString("mode") ?: "DOCUMENT"
                val cameraMode = when (modeStr.uppercase()) {
                    "ID_CARD" -> ScanCameraMode.ID_CARD
                    "BATCH" -> ScanCameraMode.BATCH
                    else -> ScanCameraMode.DOCUMENT
                }
                val docId = backStackEntry.arguments?.getLong("docId") ?: 0L
                val replacePageId = backStackEntry.arguments?.getLong("replacePageId") ?: 0L

                CameraScanScreen(
                    initialMode = cameraMode,
                    docId = docId,
                    replacePageId = replacePageId,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onDocumentCaptured = { pages ->
                        if (replacePageId > 0L) {
                            val firstPage = pages.firstOrNull()
                            if (firstPage != null) {
                                docViewModel.replacePage(replacePageId, firstPage.first, firstPage.second)
                            }
                            navController.popBackStack()
                        } else if (docId > 0L) {
                            docViewModel.addPagesToCurrentDocument(pages)
                            navController.popBackStack()
                        } else {
                            docViewModel.importPagesAsDocument(pages) { newDocId ->
                                navController.navigate(Screen.DocumentViewer.createRoute(newDocId)) {
                                    popUpTo(Screen.Home.route)
                                }
                            }
                        }
                    },
                    onIdCardCaptured = { frontPath, backPath ->
                        navController.navigate(Screen.IdCardMerger.createRoute(frontPath, backPath, docId))
                    }
                )
            }

            // ID Card Merger Screen
            composable(
                route = Screen.IdCardMerger.route,
                arguments = listOf(
                    navArgument("front") { type = NavType.StringType },
                    navArgument("back") { type = NavType.StringType },
                    navArgument("docId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { backStackEntry ->
                val front = Uri.decode(backStackEntry.arguments?.getString("front") ?: "")
                val back = Uri.decode(backStackEntry.arguments?.getString("back") ?: "")
                val targetDocId = backStackEntry.arguments?.getLong("docId") ?: 0L

                IdCardMergerScreen(
                    frontImagePath = front,
                    backImagePath = back,
                    onMerged = { mergedPath ->
                        if (targetDocId > 0L) {
                            docViewModel.addPagesToCurrentDocument(listOf(Pair(front, mergedPath)))
                            navController.navigate(Screen.DocumentViewer.createRoute(targetDocId)) {
                                popUpTo(Screen.DocumentViewer.route)
                            }
                        } else {
                            docViewModel.importPagesAsDocument(listOf(Pair(front, mergedPath))) { newDocId ->
                                navController.navigate(Screen.DocumentViewer.createRoute(newDocId)) {
                                    popUpTo(Screen.Home.route)
                                }
                            }
                        }
                    },
                    onCancel = {
                        navController.popBackStack()
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
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
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
                    }
                )
            }

            // Crop & Document Editor Screen
            composable(
                route = Screen.CropEditor.route,
                arguments = listOf(
                    navArgument("docId") { type = NavType.LongType },
                    navArgument("pageId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val pageId = backStackEntry.arguments?.getLong("pageId") ?: 0L
                val activePage = docUiState.activePages.find { it.id == pageId }
                val imagePath = activePage?.rawImagePath ?: activePage?.processedImagePath ?: ""

                DocumentCropEditorScreen(
                    imagePath = imagePath,
                    onCropped = { newProcessedPath ->
                        docViewModel.updateActivePageProcessedImage(newProcessedPath)
                        navController.popBackStack()
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
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
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
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
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Settings Screen
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
