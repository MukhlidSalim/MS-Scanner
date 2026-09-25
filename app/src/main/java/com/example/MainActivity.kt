package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.ocr.OcrScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.viewer.DocumentViewerScreen
import com.example.ui.theme.DocScanTheme
import com.example.ui.viewmodel.DocumentViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = DocScanDatabase.getInstance(applicationContext)
        val repository = DocumentRepository(applicationContext, database.documentDao())

        setContent {
            val docViewModel = remember { DocumentViewModel(applicationContext, repository) }

            DocScanTheme {
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
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = docViewModel,
                    onNavigateToScan = {
                        navController.navigate(Screen.CameraScan.route)
                    },
                    onNavigateToDocument = { docId ->
                        navController.navigate(Screen.DocumentViewer.createRoute(docId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

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
                    onNavigateToCrop = { dId, pageId ->
                        navController.navigate(Screen.Crop.createRoute(dId, pageId))
                    },
                    onNavigateToOcr = { dId, pageId ->
                        navController.navigate(Screen.Ocr.createRoute(dId, pageId))
                    },
                    onNavigateToAnnotate = { dId, pageId ->
                        navController.navigate(Screen.Annotate.createRoute(dId, pageId))
                    }
                )
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
                OcrScreen(
                    docId = docId,
                    pageId = pageId,
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
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
                AnnotationScreen(
                    docId = docId,
                    pageId = pageId,
                    viewModel = docViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

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
