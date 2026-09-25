import re

file_path = r'C:\Users\Mukhlid\antigravity\DocScan-Pro\app\src\main\java\com\example\ui\screens\settings\SettingsScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

replacements = [
    (r'"100% Offline-First Architecture"', r'stringResource(R.string.txt_offline_architecture)'),
    (r'"All scans, documents, signatures, and OCR processing remain strictly on your device\. Zero external cloud leaks\."', r'stringResource(R.string.txt_offline_desc)'),
    (r'"Language / اللغة"', r'stringResource(R.string.txt_language)'),
    (r'"App Language"', r'stringResource(R.string.txt_app_language)'),
    (r'"Switch between English and Arabic"', r'stringResource(R.string.txt_switch_lang_desc)'),
    (r'"Appearance"', r'stringResource(R.string.txt_appearance)'),
    (r'"App Theme"', r'stringResource(R.string.txt_app_theme)'),
    (r'"Default PDF Export Settings"', r'stringResource(R.string.txt_default_pdf_settings)'),
    (r'"Page Size"', r'stringResource(R.string.txt_page_size)'),
    (r'"Compression & Quality"', r'stringResource(R.string.txt_compression_quality)'),
    (r'"Cloud Sync & Backup"', r'stringResource(R.string.txt_cloud_sync)'),
    (r'"Auto-save to Gallery"', r'stringResource(R.string.txt_auto_save_gallery)'),
    (r'"Save a copy of scanned pages to photos"', r'stringResource(R.string.txt_auto_save_desc)'),
    (r'"Google Drive Sync"', r'stringResource(R.string.txt_gdrive_sync)'),
    (r'"Securely backup your documents"', r'stringResource(R.string.txt_gdrive_desc)'),
    (r'"Connect"', r'stringResource(R.string.txt_connect)'),
    (r'"Security & PIN Lock"', r'stringResource(R.string.txt_security_pin)'),
    (r'"PIN configured and active"', r'stringResource(R.string.txt_pin_configured)'),
    (r'"No PIN configured"', r'stringResource(R.string.txt_no_pin)'),
    (r'"Change PIN"', r'stringResource(R.string.txt_change_pin)'),
    (r'"Set PIN"', r'stringResource(R.string.txt_set_pin)'),
    (r'"Legal"', r'stringResource(R.string.txt_legal)'),
    (r'"Privacy Policy"', r'stringResource(R.string.txt_privacy_policy)'),
    (r'"Storage Management"', r'stringResource(R.string.txt_storage_management)'),
    (r'"About DocScan Pro"', r'stringResource(R.string.txt_about)'),
    (r'"Professional Document Scanner with Intelligent Boundary Detection, 4-Corner Homography Perspective Correction, Deep OCR AI, and Searchable Multi-Page PDF Studio\."', r'stringResource(R.string.txt_professional_document_scan)'),
    (r'"Coming soon"', r'context.getString(R.string.txt_coming_soon)'),
    (r'"PIN disabled"', r'context.getString(R.string.txt_pin_disabled)'),
    (r'"Temporary cache cleared!"', r'context.getString(R.string.txt_cache_cleared)'),
    (r'"Trash emptied!"', r'context.getString(R.string.txt_trash_emptied)'),
    (r'"PIN set successfully!"', r'context.getString(R.string.txt_pin_set_success)'),
    (r'"PIN must be exactly 4 digits"', r'context.getString(R.string.txt_pin_4_digits)'),
]

for old, new in replacements:
    content = re.sub(old, new, content)

# Theme chips replacement
old_theme_loop = r'''listOf\("System", "Light", "Dark"\)\.forEach \{ theme ->
                            FilterChip\(
                                selected = uiState\.themeMode == theme,
                                onClick = \{ viewModel\.setThemeMode\(theme\) \},
                                label = \{ Text\(theme\) \}
                            \)
                        \}'''

new_theme_loop = r'''val themeOptions = listOf("System" to stringResource(R.string.txt_system), "Light" to stringResource(R.string.txt_light), "Dark" to stringResource(R.string.txt_dark))
                        themeOptions.forEach { (themeId, themeLabel) ->
                            FilterChip(
                                selected = uiState.themeMode == themeId,
                                onClick = { viewModel.setThemeMode(themeId) },
                                label = { Text(themeLabel) }
                            )
                        }'''

content = re.sub(old_theme_loop, new_theme_loop, content)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Replacements done.")
