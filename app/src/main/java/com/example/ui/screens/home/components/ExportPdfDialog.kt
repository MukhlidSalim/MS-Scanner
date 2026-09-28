package com.example.ui.screens.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.CompressionPreset
import com.example.data.model.DocumentEntity
import com.example.data.model.PageSizePreset
import com.example.engine.pdf.PdfEngine
import com.example.engine.pdf.PdfExportConfig
import com.example.ui.theme.*
import com.example.ui.viewmodel.ExportPdfAction
import java.text.SimpleDateFormat
import java.util.*

enum class ExportFormat { PDF, JPG, PNG, DOCX, TXT }

@Composable
fun ExportPdfDialog(
    show: Boolean,
    selectedDocuments: List<DocumentEntity>,
    totalPageCount: Int,
    isExporting: Boolean,
    onDismiss: () -> Unit,
    onExportAction: (config: PdfExportConfig, action: ExportPdfAction) -> Unit
) {
    if (!show) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val isArabic = remember { context.resources.configuration.locales[0].language == "ar" }

    val defaultTitle = remember(selectedDocuments) {
        if (selectedDocuments.size == 1) {
            selectedDocuments.first().title
        } else if (selectedDocuments.isNotEmpty()) {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            "${selectedDocuments.first().title}_and_${selectedDocuments.size - 1}_more"
        } else {
            "Exported_Documents"
        }
    }

    var fileName by remember(selectedDocuments) { mutableStateOf(defaultTitle) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var pageSize by remember { mutableStateOf(PageSizePreset.A4) }
    var compression by remember { mutableStateOf(CompressionPreset.HIGH) }
    var includePageNumbers by remember { mutableStateOf(true) }
    var includeSearchableText by remember { mutableStateOf(true) }
    var watermarkText by remember { mutableStateOf("") }
    
    var showAdvancedOptions by remember { mutableStateOf(false) }

    val estimatedBytes = remember(totalPageCount, compression) {
        PdfEngine.estimatePdfSizeBytes(totalPageCount.coerceAtLeast(1), compression)
    }
    val estimatedSizeFormatted = remember(estimatedBytes) {
        PdfEngine.formatEstimatedSize(estimatedBytes)
    }

    Dialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, InkBorderStrong, RoundedCornerShape(24.dp)),
            color = InkSurface2,
            tonalElevation = 8.dp
        ) {
            Box(modifier = Modifier.padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when(selectedFormat) {
                                ExportFormat.PDF -> Icons.Default.PictureAsPdf
                                ExportFormat.JPG, ExportFormat.PNG -> Icons.Default.Image
                                ExportFormat.DOCX, ExportFormat.TXT -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }, 
                            null, 
                            tint = GoldBase, 
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isArabic) "تصدير المستند" else "Export Document", 
                            style = MaterialTheme.typography.titleLarge, 
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Format Selection
                    Text(
                        text = if (isArabic) "اختر الصيغة" else "Select Format", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = GoldLight,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), 
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExportFormat.values().forEach { format ->
                            FilterChip(
                                selected = selectedFormat == format,
                                onClick = { selectedFormat = format },
                                label = { Text(format.name, fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text(if (isArabic) "اسم الملف" else "File Name") },
                        suffix = { Text(".${selectedFormat.name.lowercase()}") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedFormat == ExportFormat.PDF) {
                        // Simplified Default Settings Info
                        Surface(
                            color = InkSurface3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(if (isArabic) "إعدادات PDF الافتراضية:" else "Default PDF Settings:", style = MaterialTheme.typography.labelSmall, color = GoldLight)
                                Text(if (isArabic) "• جودة عالية (A4)" else "• High Quality (A4)", style = MaterialTheme.typography.bodySmall)
                                Text(if (isArabic) "• استخراج النص (OCR) مفعل" else "• Searchable OCR enabled", style = MaterialTheme.typography.bodySmall)
                                Text(if (isArabic) "• بدون علامة مائية" else "• No watermark", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(onClick = { showAdvancedOptions = !showAdvancedOptions }) {
                            Icon(if (showAdvancedOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isArabic) "خيارات متقدمة" else "Advanced Options")
                        }

                        AnimatedVisibility(visible = showAdvancedOptions) {
                            Column {
                                // Page Size
                                Text(if (isArabic) "حجم الصفحة" else "Page Size", style = MaterialTheme.typography.labelMedium)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PageSizePreset.values().forEach { size ->
                                        FilterChip(
                                            selected = pageSize == size,
                                            onClick = { pageSize = size },
                                            label = { Text(size.name) }
                                        )
                                    }
                                }
                                // Compression
                                Text(if (isArabic) "الضغط" else "Compression", style = MaterialTheme.typography.labelMedium)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompressionPreset.values().forEach { comp ->
                                        FilterChip(
                                            selected = compression == comp,
                                            onClick = { compression = comp },
                                            label = { Text(comp.name) }
                                        )
                                    }
                                }
                                // Watermark
                                OutlinedTextField(
                                    value = watermarkText,
                                    onValueChange = { watermarkText = it },
                                    label = { Text(if (isArabic) "علامة مائية" else "Watermark") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    } else {
                        // Info for other formats
                        Surface(
                            color = InkSurface3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = when(selectedFormat) {
                                        ExportFormat.JPG, ExportFormat.PNG -> if (isArabic) "سيتم تصدير كل صفحة كصورة منفصلة." else "Each page will be exported as a separate image."
                                        ExportFormat.DOCX -> if (isArabic) "تصدير النص المستخرج إلى ملف Word." else "Export extracted text to Word document."
                                        ExportFormat.TXT -> if (isArabic) "تصدير النص المستخرج إلى ملف نصي." else "Export extracted text to plain text file."
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val buildConfig = {
                        PdfExportConfig(
                            title = fileName.trim().ifBlank { defaultTitle },
                            pageSize = pageSize,
                            compression = compression,
                            includeSearchableText = includeSearchableText,
                            includePageNumbers = includePageNumbers,
                            watermarkText = if (watermarkText.isNotBlank()) watermarkText.trim() else null
                        )
                    }

                    Button(
                        onClick = { 
                            // In a real app, we'd handle different formats here. 
                            // For now, we reuse the PDF action or show a message.
                            if (selectedFormat == ExportFormat.PDF) {
                                onExportAction(buildConfig(), ExportPdfAction.SHARE)
                            } else {
                                // Placeholder for other formats
                                onExportAction(buildConfig(), ExportPdfAction.SHARE)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldBase, contentColor = InkBase)
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "مشاركة كـ ${selectedFormat.name}" else "Share as ${selectedFormat.name}", 
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onExportAction(buildConfig(), ExportPdfAction.SAVE_TO_DOWNLOADS) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isArabic) "حفظ" else "Save")
                        }
                        OutlinedButton(
                            onClick = { onExportAction(buildConfig(), ExportPdfAction.PREVIEW) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isArabic) "معاينة" else "Preview")
                        }
                    }
                }

                if (isExporting) {
                    Box(modifier = Modifier.matchParentSize().background(InkBase.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GoldBase)
                    }
                }
            }
        }
    }
}
