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
    var pageSize by remember { mutableStateOf(PageSizePreset.A4) }
    var compression by remember { mutableStateOf(CompressionPreset.HIGH) }
    var includePageNumbers by remember { mutableStateOf(true) }
    var includeSearchableText by remember { mutableStateOf(true) }
    var showWatermarkField by remember { mutableStateOf(false) }
    var watermarkText by remember { mutableStateOf("") }

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
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, InkBorderStrong, RoundedCornerShape(24.dp)),
            color = InkSurface2,
            tonalElevation = 8.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GoldBase.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = GoldBase,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.export_pdf_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(
                                    R.string.export_pdf_subtitle,
                                    selectedDocuments.size,
                                    totalPageCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            enabled = !isExporting,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.txt_cancel),
                                tint = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = InkBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Scrollable Configuration Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // File Name Input
                        Text(
                            text = stringResource(R.string.export_pdf_file_name),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GoldLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            singleLine = true,
                            enabled = !isExporting,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = GoldBase
                                )
                            },
                            trailingIcon = {
                                if (fileName.isNotBlank() && !isExporting) {
                                    IconButton(onClick = { fileName = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                                    }
                                }
                            },
                            suffix = {
                                Text(".pdf", color = TextSecondary, fontWeight = FontWeight.Bold)
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = InkSurface3,
                                unfocusedContainerColor = InkSurface3,
                                focusedBorderColor = GoldBase,
                                unfocusedBorderColor = InkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Estimated Size & Quality Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.export_pdf_quality),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GoldLight
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = InkSurface3
                            ) {
                                Text(
                                    text = stringResource(R.string.export_pdf_estimated_size, estimatedSizeFormatted),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SemanticSuccess,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Compression presets selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val presets = listOf(
                                Triple(CompressionPreset.LOW, "Low", "سريع/صغير"),
                                Triple(CompressionPreset.MEDIUM, "Med", "متوازن"),
                                Triple(CompressionPreset.HIGH, "High", "عالي"),
                                Triple(CompressionPreset.MAXIMUM, "Max", "أصلي")
                            )
                            presets.forEach { (preset, enLabel, arLabel) ->
                                val isSelected = compression == preset
                                val label = "$enLabel"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) GoldBase else InkSurface3)
                                        .clickable(enabled = !isExporting) { compression = preset }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) InkBase else TextPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Page Size
                        Text(
                            text = stringResource(R.string.export_pdf_page_size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GoldLight
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val sizes = listOf(
                                Pair(PageSizePreset.A4, "A4"),
                                Pair(PageSizePreset.LETTER, "Letter"),
                                Pair(PageSizePreset.FIT_ORIGINAL, "Original"),
                                Pair(PageSizePreset.LEGAL, "Legal")
                            )
                            sizes.forEach { (sizePreset, name) ->
                                val isSelected = pageSize == sizePreset
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) GoldBase else InkSurface3)
                                        .clickable(enabled = !isExporting) { pageSize = sizePreset }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) InkBase else TextPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Toggles: Page Numbers & Searchable OCR
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(InkSurface3)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.export_pdf_page_numbers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Switch(
                                checked = includePageNumbers,
                                onCheckedChange = { includePageNumbers = it },
                                enabled = !isExporting,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = InkBase,
                                    checkedTrackColor = GoldBase,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = InkSurface1
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(InkSurface3)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.export_pdf_searchable_ocr),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Switch(
                                checked = includeSearchableText,
                                onCheckedChange = { includeSearchableText = it },
                                enabled = !isExporting,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = InkBase,
                                    checkedTrackColor = GoldBase,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = InkSurface1
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Optional Watermark toggle & input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showWatermarkField = !showWatermarkField },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (showWatermarkField) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = GoldLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.export_pdf_watermark),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GoldLight
                            )
                        }

                        AnimatedVisibility(visible = showWatermarkField) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedTextField(
                                    value = watermarkText,
                                    onValueChange = { watermarkText = it },
                                    placeholder = {
                                        Text(
                                            stringResource(R.string.export_pdf_watermark_hint),
                                            color = TextTertiary
                                        )
                                    },
                                    singleLine = true,
                                    enabled = !isExporting,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = InkSurface3,
                                        unfocusedContainerColor = InkSurface3,
                                        focusedBorderColor = GoldBase,
                                        unfocusedBorderColor = InkBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = InkBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons: Share, Save on Device, Save As, Open
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

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Main Call-To-Action: Review & Share PDF (opens in-app PDF preview)
                        Button(
                            onClick = { onExportAction(buildConfig(), ExportPdfAction.PREVIEW) },
                            enabled = !isExporting,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GoldBase,
                                contentColor = InkBase
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.pdf_preview_review_btn),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Row: Direct Share & Save on Device
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onExportAction(buildConfig(), ExportPdfAction.SHARE) },
                                enabled = !isExporting,
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, InkBorderStrong),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = InkSurface3,
                                    contentColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = GoldLight, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.export_pdf_action_share),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = { onExportAction(buildConfig(), ExportPdfAction.SAVE_TO_DOWNLOADS) },
                                enabled = !isExporting,
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, InkBorderStrong),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = InkSurface3,
                                    contentColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    tint = GoldLight,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.export_pdf_action_save_downloads),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Secondary Action: Save As... (SAF)
                        OutlinedButton(
                            onClick = { onExportAction(buildConfig(), ExportPdfAction.SAVE_AS) },
                            enabled = !isExporting,
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkBorderStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.export_pdf_action_save_as),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Loading Overlay when PDF generation is in progress
                if (isExporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(InkBase.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = GoldBase,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.export_pdf_generating),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
