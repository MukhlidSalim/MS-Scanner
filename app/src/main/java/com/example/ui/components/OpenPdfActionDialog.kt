package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.*
import java.io.File
import java.util.Locale

@Composable
fun OpenPdfActionDialog(
    pdfFile: File,
    isConverting: Boolean = false,
    conversionProgress: Pair<Int, Int>? = null,
    onDismiss: () -> Unit,
    onBrowseOnly: () -> Unit,
    onEditInStudio: () -> Unit
) {
    val formattedSize = remember(pdfFile) {
        val bytes = pdfFile.length()
        if (bytes < 1024 * 1024) {
            "${bytes / 1024} KB"
        } else {
            String.format(Locale.US, "%.1f MB", bytes.toFloat() / (1024f * 1024f))
        }
    }

    Dialog(
        onDismissRequest = { if (!isConverting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, InkBorderStrong, RoundedCornerShape(26.dp)),
            color = InkSurface2,
            tonalElevation = 8.dp
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    // Header with Icon & Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
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

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pdf_open_dialog_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.pdf_open_dialog_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            enabled = !isConverting,
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

                    // File summary card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = InkSurface3,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = GoldLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = pdfFile.nameWithoutExtension.ifBlank { "Document" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = InkSurface4
                            ) {
                                Text(
                                    text = formattedSize,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldLight,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Option 1: Browse & Read Only
                    PdfOptionCard(
                        icon = Icons.Default.Visibility,
                        iconTint = GoldBase,
                        iconBg = GoldBase.copy(alpha = 0.15f),
                        title = stringResource(R.string.pdf_open_view_title),
                        description = stringResource(R.string.pdf_open_view_desc),
                        badgeText = if (java.util.Locale.getDefault().language == "ar") "سريع" else "Fast",
                        badgeColor = SemanticSuccess,
                        enabled = !isConverting,
                        onClick = onBrowseOnly
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 2: Edit in Studio
                    PdfOptionCard(
                        icon = Icons.Default.AutoFixHigh,
                        iconTint = Emerald400,
                        iconBg = Emerald400.copy(alpha = 0.15f),
                        title = stringResource(R.string.pdf_open_edit_title),
                        description = stringResource(R.string.pdf_open_edit_desc),
                        badgeText = if (java.util.Locale.getDefault().language == "ar") "أدوات متكاملة" else "Full Tools",
                        badgeColor = Emerald400,
                        enabled = !isConverting,
                        onClick = onEditInStudio
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Dismiss Button
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isConverting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.txt_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Converting overlay
                if (isConverting) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(InkBase.copy(alpha = 0.88f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            CircularProgressIndicator(
                                color = GoldBase,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            val progressText = if (conversionProgress != null && conversionProgress.second > 0) {
                                if (java.util.Locale.getDefault().language == "ar") {
                                    "جاري تجهيز الصفحة ${conversionProgress.first} من ${conversionProgress.second}…"
                                } else {
                                    "Preparing page ${conversionProgress.first} of ${conversionProgress.second}…"
                                }
                            } else {
                                stringResource(R.string.pdf_open_converting)
                            }
                            Text(
                                text = progressText,
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

@Composable
private fun PdfOptionCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    description: String,
    badgeText: String,
    badgeColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = InkSurface3,
        border = androidx.compose.foundation.BorderStroke(1.dp, InkBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
