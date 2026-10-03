package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.PageEntity
import java.io.File

/**
 * Actions for ONE page (opened from "More" on the document screen).
 * Grouped by purpose: Edit · Organize · Share & export · Delete. Labels describe exactly what each
 * action does (the former "DOCX" entry produced a Word-compatible .doc file; "Merge" was a large
 * "NEW" banner that pushed the everyday actions down).
 */
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
    fun t(en: String, ar: String) = if (isArabic) ar else en

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = File(page.processedImagePath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = t("Page ${pageIndex + 1} of $totalPages", "الصفحة ${pageIndex + 1} من $totalPages"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = t("Close", "إغلاق"))
                }
            }

            ActionGroup(t("Edit", "تعديل")) {
                PageActionItem(Icons.Default.Crop, t("Crop & adjust", "قص وضبط"), Modifier.weight(1f)) { onDismiss(); onEditCropClick() }
                PageActionItem(Icons.Default.RotateRight, t("Rotate 90°", "تدوير 90°"), Modifier.weight(1f)) { onDismiss(); onRotateClick() }
                PageActionItem(Icons.Default.Draw, t("Sign & annotate", "توقيع وتعليق"), Modifier.weight(1f)) { onDismiss(); onAnnotateClick() }
                PageActionItem(Icons.Default.TextFields, t("Extract text", "استخراج النص"), Modifier.weight(1f)) { onDismiss(); onOcrClick() }
            }
            ActionGroup(t("Organize", "تنظيم")) {
                PageActionItem(Icons.Default.PhotoLibrary, t("Replace image", "استبدال الصورة"), Modifier.weight(1f)) { onDismiss(); onReplaceClick() }
                PageActionItem(Icons.Default.FileCopy, t("Duplicate", "تكرار"), Modifier.weight(1f)) { onDismiss(); onDuplicateClick() }
                PageActionItem(Icons.AutoMirrored.Filled.MergeType, t("Merge pages", "دمج صفحات"), Modifier.weight(1f)) { onDismiss(); onMergeClick() }
                PageActionItem(Icons.Default.Print, t("Print page", "طباعة الصفحة"), Modifier.weight(1f)) { onDismiss(); onPrintClick() }
            }
            ActionGroup(t("Share & export this page", "مشاركة وتصدير هذه الصفحة")) {
                PageActionItem(Icons.Default.Share, t("Share image", "مشاركة الصورة"), Modifier.weight(1f)) { onDismiss(); onShareClick() }
                PageActionItem(Icons.Default.Image, t("PNG image", "صورة PNG"), Modifier.weight(1f)) { onDismiss(); onExportPngClick() }
                PageActionItem(Icons.Default.TextSnippet, t("Text (.txt)", "نص (.txt)"), Modifier.weight(1f)) { onDismiss(); onExportTxtClick() }
                PageActionItem(Icons.Default.Description, t("Word (.doc)", "وورد (.doc)"), Modifier.weight(1f)) { onDismiss(); onExportDocxClick() }
            }
            OutlinedButton(
                onClick = { onDismiss(); onDeleteClick() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(t("Delete this page", "حذف هذه الصفحة"))
            }
        }
    }
}

@Composable
private fun ActionGroup(title: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), content = content)
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
        modifier = modifier.padding(2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
