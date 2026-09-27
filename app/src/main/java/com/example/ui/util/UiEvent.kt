package com.example.ui.util

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class ShowSnackbar(val message: String) : UiEvent()
    data class Navigate(val route: String) : UiEvent()
    data object Success : UiEvent()
    data class Error(val message: String) : UiEvent()
}
