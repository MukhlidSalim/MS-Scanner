package com.example.ui.screens.viewer.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Emerald400

@Composable
fun SelectionActionBar(
    selectedPageIds: Set<Long>,
    isArabic: Boolean,
    onMerge: () -> Unit,
    onShare: () -> Unit,
    onExportPdf: () -> Unit,
    onPrint: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionItem(
                icon = Icons.AutoMirrored.Filled.MergeType,
                label = if (isArabic) "دمج" else "Merge",
                enabled = selectedPageIds.isNotEmpty(),
                tint = Emerald400,
                onClick = onMerge
            )
            ActionItem(
                icon = Icons.Default.Share,
                label = if (isArabic) "مشاركة" else "Share",
                enabled = selectedPageIds.isNotEmpty(),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onShare
            )
            ActionItem(
                icon = Icons.Default.PictureAsPdf,
                label = "PDF",
                enabled = selectedPageIds.isNotEmpty(),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onExportPdf
            )
            ActionItem(
                icon = Icons.Default.Print,
                label = if (isArabic) "طباعة" else "Print",
                enabled = selectedPageIds.isNotEmpty(),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onPrint
            )
            ActionItem(
                icon = Icons.Default.FileCopy,
                label = if (isArabic) "تكرار" else "Copy",
                enabled = selectedPageIds.isNotEmpty(),
                tint = MaterialTheme.colorScheme.secondary,
                onClick = onDuplicate
            )
            ActionItem(
                icon = Icons.Default.Delete,
                label = if (isArabic) "حذف" else "Delete",
                enabled = selectedPageIds.isNotEmpty(),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
