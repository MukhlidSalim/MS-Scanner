package com.example.ui.screens.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CompressionPreset
import com.example.data.model.PageSizePreset
import com.example.engine.pdf.PdfEngine
import com.example.ui.theme.Emerald400

/*
 * Save & Share sheet and advanced PDF settings of the document screen.
 * Extracted from DocumentViewerScreen.kt (same behaviour) to keep that screen maintainable.
 */

/** What Save / Share / Print applies to. */
enum class ShareScope { DOCUMENT, CURRENT_PAGE, SELECTED }

/** The single Save & Share entry point (document or selected pages). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveShareSheet(
    isArabic: Boolean,
    pageCount: Int,
    isSelection: Boolean,
    onDismiss: () -> Unit,
    /** Scope choice (shown for multi-page documents): Entire document / Current page / Selected pages. */
    scope: ShareScope = if (isSelection) ShareScope.SELECTED else ShareScope.DOCUMENT,
    documentPageCount: Int = pageCount,
    currentPageNumber: Int = 1,
    selectedCount: Int = 0,
    onScopeChange: (ShareScope) -> Unit = {},
    onSelectPages: () -> Unit = {},
    onSavePdf: () -> Unit,
    onSavePdfAs: () -> Unit,
    onSaveImages: () -> Unit,
    onSharePdf: () -> Unit,
    onShareImages: () -> Unit,
    onPrint: () -> Unit,
    onPdfSettings: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(t("Save & Share", "حفظ ومشاركة"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (documentPageCount > 1) {
                // Explicit choice: nothing is hidden behind long-press or selection modes.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = scope == ShareScope.DOCUMENT,
                        onClick = { onScopeChange(ShareScope.DOCUMENT) },
                        label = { Text(t("Entire document ($documentPageCount)", "المستند كاملاً ($documentPageCount)")) }
                    )
                    FilterChip(
                        selected = scope == ShareScope.CURRENT_PAGE,
                        onClick = { onScopeChange(ShareScope.CURRENT_PAGE) },
                        label = { Text(t("Current page ($currentPageNumber)", "الصفحة الحالية ($currentPageNumber)")) }
                    )
                    FilterChip(
                        selected = scope == ShareScope.SELECTED,
                        onClick = { if (selectedCount > 0) onScopeChange(ShareScope.SELECTED) else onSelectPages() },
                        label = {
                            Text(
                                if (selectedCount > 0) t("Selected pages ($selectedCount)", "الصفحات المحددة ($selectedCount)")
                                else t("Select pages…", "تحديد صفحات…")
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(16.dp)) }
                    )
                }
                if (scope == ShareScope.SELECTED && selectedCount > 0) {
                    TextButton(onClick = onSelectPages) { Text(t("Change selection", "تغيير التحديد")) }
                }
            }
            Text(
                when (scope) {
                    ShareScope.DOCUMENT -> t("Whole document · $pageCount page(s)", "المستند كاملاً · $pageCount صفحة")
                    ShareScope.CURRENT_PAGE -> t("Page $currentPageNumber only", "الصفحة $currentPageNumber فقط")
                    ShareScope.SELECTED -> t("$pageCount selected page(s), in document order", "$pageCount صفحة محددة، بترتيب المستند")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            SheetSection(t("Share", "مشاركة"))
            SheetAction(Icons.Default.PictureAsPdf, t("Share as PDF", "مشاركة كملف PDF"), t("One PDF file", "ملف PDF واحد"), Emerald400, onSharePdf)
            SheetAction(Icons.Default.Collections, t("Share as images", "مشاركة كصور"), t("JPEG, one per page", "صورة JPEG لكل صفحة"), Emerald400, onShareImages)
            SheetSection(t("Save to device", "الحفظ على الجهاز"))
            SheetAction(Icons.Default.Download, t("Save as PDF", "حفظ كملف PDF"), t("Downloads / MS Scanner", "التنزيلات / MS Scanner"), MaterialTheme.colorScheme.primary, onSavePdf)
            SheetAction(Icons.Default.FolderOpen, t("Save PDF as…", "حفظ PDF باسم…"), t("Choose name and location", "اختيار الاسم والمكان"), MaterialTheme.colorScheme.primary, onSavePdfAs)
            SheetAction(Icons.Default.Image, t("Save as images", "حفظ كصور"), t("Gallery / MS Scanner", "المعرض / MS Scanner"), MaterialTheme.colorScheme.primary, onSaveImages)
            SheetSection(t("More", "المزيد"))
            SheetAction(Icons.Default.Print, t("Print", "طباعة"), t("Printer or system “Save as PDF”", "طابعة أو حفظ PDF من النظام"), MaterialTheme.colorScheme.secondary, onPrint)
            SheetAction(Icons.Default.Tune, t("PDF settings…", "إعدادات PDF…"), t("Page size, quality, watermark", "حجم الصفحة، الجودة، العلامة المائية"), MaterialTheme.colorScheme.secondary, onPdfSettings)
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Advanced PDF export (page size, quality, OCR layer, numbers, watermark) with in-app preview. */
@Composable
internal fun PdfSettingsDialog(
    isArabic: Boolean,
    pageCount: Int,
    initialSize: PageSizePreset,
    initialCompression: CompressionPreset,
    isExporting: Boolean,
    onDismiss: () -> Unit,
    /** size, compression, OCR layer, page numbers, watermark, password (null = none) */
    onExport: (PageSizePreset, CompressionPreset, Boolean, Boolean, String?, String?) -> Unit,
    onPrint: () -> Unit
) {
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var selectedSize by remember { mutableStateOf(initialSize) }
    var selectedCompression by remember { mutableStateOf(initialCompression) }
    var includeOcr by remember { mutableStateOf(true) }
    var includePageNumbers by remember { mutableStateOf(true) }
    var selectedWatermark by remember { mutableStateOf<String?>(null) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    val passwordOk = !protect || (password.length >= 4 && password == passwordConfirm)
    val formattedSize = remember(selectedCompression, pageCount) {
        PdfEngine.formatEstimatedSize(PdfEngine.estimatePdfSizeBytes(pageCount, selectedCompression))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.txt_export_document_as_pdf), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    t("Estimated size: ~$formattedSize ($pageCount pages)", "الحجم التقريبي: ~$formattedSize ($pageCount صفحات)"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.txt_page_format), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageSizePreset.values().forEach { size ->
                        FilterChip(selected = size == selectedSize, onClick = { selectedSize = size }, label = { Text(size.name, fontSize = 11.sp) })
                    }
                }
                Text(stringResource(R.string.txt_quality___compression), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompressionPreset.values().forEach { comp ->
                        val label = when (comp) {
                            CompressionPreset.LOW -> t("Small", "صغير")
                            CompressionPreset.MEDIUM -> t("Medium", "متوسط")
                            CompressionPreset.HIGH -> t("High (Print)", "عالي (طباعة)")
                            CompressionPreset.MAXIMUM -> t("Maximum", "أقصى دقة")
                        }
                        FilterChip(selected = comp == selectedCompression, onClick = { selectedCompression = comp }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                Text(t("Watermark (optional)", "العلامة المائية (اختياري)"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        null to t("None", "بدون"),
                        "CONFIDENTIAL" to t("Confidential", "سري"),
                        "APPROVED" to t("Approved", "معتمد"),
                        "DRAFT" to t("Draft", "مسودة")
                    ).forEach { (wm, label) ->
                        FilterChip(selected = selectedWatermark == wm, onClick = { selectedWatermark = wm }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Include page numbers", "ترقيم الصفحات"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includePageNumbers, onCheckedChange = { includePageNumbers = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.txt_searchable_ocr_text_layer), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeOcr, onCheckedChange = { includeOcr = it })
                }
                // Password protection (AES-128). The password is not stored anywhere.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Protect with password", "حماية بكلمة مرور"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = protect, onCheckedChange = { protect = it })
                }
                if (protect) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(t("Password (min. 4)", "كلمة المرور (4 أحرف على الأقل)")) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it },
                        label = { Text(t("Confirm password", "تأكيد كلمة المرور")) },
                        singleLine = true,
                        isError = passwordConfirm.isNotEmpty() && passwordConfirm != password,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        t("Keep it safe: a lost password cannot be recovered.", "احتفظ بها: لا يمكن استرجاع كلمة مرور مفقودة."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onPrint) {
                    Icon(Icons.Default.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("Print / system Save as PDF", "طباعة / حفظ PDF من النظام"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onExport(selectedSize, selectedCompression, includeOcr, includePageNumbers, selectedWatermark,
                        password.takeIf { protect && it.isNotBlank() })
                },
                enabled = !isExporting && passwordOk,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("export_pdf_confirm_btn")
            ) {
                if (isExporting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.txt_export___share), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.txt_cancel)) } }
    )
}
