package com.example.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")

    object CameraScan : Screen("camera_scan?mode={mode}&docId={docId}&replacePageId={replacePageId}") {
        fun createRoute(mode: String = "DOCUMENT", docId: Long = 0L, replacePageId: Long = 0L) =
            "camera_scan?mode=$mode&docId=$docId&replacePageId=$replacePageId"
    }

    object DocumentViewer : Screen("document_viewer/{docId}") {
        fun createRoute(docId: Long) = "document_viewer/$docId"
    }

    object CropEditor : Screen("crop_editor/{docId}/{pageId}") {
        fun createRoute(docId: Long, pageId: Long) = "crop_editor/$docId/$pageId"
    }

    object Ocr : Screen("ocr/{docId}/{pageId}") {
        fun createRoute(docId: Long, pageId: Long) = "ocr/$docId/$pageId"
    }

    object Annotate : Screen("annotate/{docId}/{pageId}") {
        fun createRoute(docId: Long, pageId: Long) = "annotate/$docId/$pageId"
    }

    object IdCardMerger : Screen("id_card_merger?front={front}&back={back}&docId={docId}&isPassport={isPassport}") {
        fun createRoute(front: String, back: String, docId: Long = 0L, isPassport: Boolean = false) =
            "id_card_merger?front=${Uri.encode(front)}&back=${Uri.encode(back)}&docId=$docId&isPassport=$isPassport"
    }

    object Settings : Screen("settings")
    
    object EditSession : Screen("edit_session/{docId}") {
        fun createRoute(docId: Long) = "edit_session/$docId"
    }
}
