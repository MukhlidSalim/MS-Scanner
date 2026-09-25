package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var userPin: String
        get() = prefs.getString("user_pin", "") ?: ""
        set(value) = prefs.edit().putString("user_pin", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "System") ?: "System"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var pdfPageSize: PageSizePreset
        get() = PageSizePreset.valueOf(prefs.getString("pdf_page_size", PageSizePreset.A4.name) ?: PageSizePreset.A4.name)
        set(value) = prefs.edit().putString("pdf_page_size", value.name).apply()

    var pdfCompression: CompressionPreset
        get() = CompressionPreset.valueOf(prefs.getString("pdf_compression", CompressionPreset.HIGH.name) ?: CompressionPreset.HIGH.name)
        set(value) = prefs.edit().putString("pdf_compression", value.name).apply()
}
