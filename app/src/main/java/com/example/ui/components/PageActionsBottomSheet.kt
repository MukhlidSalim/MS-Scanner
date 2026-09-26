package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.PageEntity
import com.example.ui.theme.Emerald400
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageActionsBottomSheet(
    page: PageEntity,
    pageIndex: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onMergeClick: () -> Unit,
    onEditCropClick: () -> Unit,
    onOcrClick: () -> Unit,
    onAnnotateClick: () -> Unit,
    onRotateClick: () -> Unit,
    onDuplicateClick: () -> Unit,
    onReplaceClick: () -> Unit,
    onPrintClick: () -> Unit,
    onShareClick: () -> Unit,
    onExportDocxClick: () -> Unit,
    onExportTxtClick: () -> Unit,
    onExportPngClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with thumbnail & page title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp, 72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                ) {
                    AsyncImage(
                        model = File(page.processedImagePath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isArabic) "الصفحة ${pageIndex + 1} من $totalPages" else "Page ${pageIndex + 1} of $totalPages",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isArabic) "حدد الإجراء المطلوب تنفيذه على هذه الصفحة" else "Select action for this page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // PRIMARY HIGHLIGHTED ACTION: Merge with other images into one page
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Emerald400.copy(alpha = 0.12f),
                border = BorderStroke(1.5.dp, Brush.horizontalGradient(listOf(Emerald400, Color(0xFF00E676)))),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onMergeClick()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Emerald400),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MergeType,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isArabic) "الدمج مع صور أخرى في صفحة واحدة" else "Merge into Single Page",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Emerald400
                            ) {
                                Text(
                                    text = if (isArabic) "جديد" else "NEW",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = if (isArabic)
                                "تنسيق شبكي متعدد (1×2, 2×2, 2×3) مع التحكم بحجم ومسافات الصور"
                            else
                                "Multi-grid layouts (1x2, 2x2, 2x3) with custom image scale & spacing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Emerald400
                    )
                }
            }

            // Grid / Row of Standard Default Actions
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PageActionItem(
                            icon = Icons.Default.Crop,
                            label = if (isArabic) "تعديل وقص" else "Edit & Crop",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onEditCropClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.TextFields,
                            label = if (isArabic) "استخراج النص" else "OCR Text",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onOcrClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.Draw,
                            label = if (isArabic) "رسم وتوقيع" else "Annotate",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onAnnotateClick() }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        PageActionItem(
                            icon = Icons.Default.RotateRight,
                            label = if (isArabic) "تدوير 90°" else "Rotate",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onRotateClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.FileCopy,
                            label = if (isArabic) "تكرار الصفحة" else "Duplicate",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onDuplicateClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.PhotoLibrary,
                            label = if (isArabic) "استبدال الصورة" else "Replace",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onReplaceClick() }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        PageActionItem(
                            icon = Icons.Default.Print,
                            label = if (isArabic) "طباعة" else "Print",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onPrintClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.Share,
                            label = if (isArabic) "مشاركة" else "Share",
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onShareClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.Description,
                            label = stringResource(R.string.txt_export_docx),
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onExportDocxClick() }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        PageActionItem(
                            icon = Icons.Default.TextSnippet,
                            label = stringResource(R.string.txt_export_txt),
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onExportTxtClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.Image,
                            label = stringResource(R.string.txt_export_png),
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onExportPngClick() }
                        )
                        PageActionItem(
                            icon = Icons.Default.DeleteOutline,
                            label = if (isArabic) "حذف" else "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            onClick = { onDismiss(); onDeleteClick() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageActionItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = modifier.padding(4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}
