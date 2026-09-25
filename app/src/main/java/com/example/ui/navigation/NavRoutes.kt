package com.example.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object DocumentViewer : Screen("document_viewer/{docId}") {
        fun createRoute(docId: Long) = "document_viewer/$docId"
    }
    object Ocr : Screen("ocr/{docId}/{pageId}") {
        fun createRoute(docId: Long, pageId: Long) = "ocr/$docId/$pageId"
    }
    object Annotate : Screen("annotate/{docId}/{pageId}") {
        fun createRoute(docId: Long, pageId: Long) = "annotate/$docId/$pageId"
    }
    object Settings : Screen("settings")
}
