package com.example.ui.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DocumentEntity
import com.example.ui.theme.*
import com.example.util.SearchText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    highlightColor: Color = GoldBase.copy(alpha = 0.32f),
    modifier: Modifier = Modifier
) {
    val ranges = remember(text, query) { SearchText.findMatchRanges(text, query) }
    if (ranges.isEmpty()) {
        Text(text = text, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }

    val annotatedString = buildAnnotatedString {
        var lastIndex = 0
        for (range in ranges) {
            if (range.first > lastIndex) {
                append(text.substring(lastIndex, range.first))
            }
            withStyle(style = SpanStyle(background = highlightColor)) {
                append(text.substring(range.first, (range.last + 1).coerceAtMost(text.length)))
            }
            lastIndex = (range.last + 1).coerceAtMost(text.length)
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    Text(text = annotatedString, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Document card supporting both 2-column grid mode and 1-column compact horizontal list mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridItem(
    doc: DocumentEntity,
    searchQuery: String,
    isSelected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    isCompactListView: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit = {},
    onRenameClick: (() -> Unit)? = null,
    onExportClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onFavoriteClick: (() -> Unit)? = null
) {
    val isArabic = LocalContext.current.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val catLabel = remember(doc.category, isArabic) {
        if (doc.category.isNotBlank() && doc.category != "OTHER" && doc.category != "ALL") {
            val catObj = runCatching { com.example.data.model.DocumentCategory.valueOf(doc.category) }.getOrNull()
            if (catObj != null) (if (isArabic) catObj.displayNameAr else catObj.displayNameEn) else doc.category
        } else null
    }
    val dateText = remember(doc.updatedAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(doc.updatedAt))
    }
    val metaText = if (doc.fileSizeFormatted.isNotBlank()) "$dateText • ${doc.fileSizeFormatted}" else dateText

    @Composable
    fun ActionsMenuButton() {
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.txt_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(14.dp)
            ) {
                onShareClick?.let { action ->
                    DropdownMenuItem(
                        text = { Text(t("Share as PDF", "مشاركة كـ PDF")) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = GoldLight) },
                        onClick = { expanded = false; action() }
                    )
                }
                onExportClick?.let { action ->
                    DropdownMenuItem(
                        text = { Text(t("Export PDF…", "تصدير PDF…")) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, tint = GoldBase) },
                        onClick = { expanded = false; action() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(t("Edit pages", "تعديل الصفحات")) },
                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, tint = Emerald400) },
                    onClick = { expanded = false; onEditClick() }
                )
                onRenameClick?.let { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { expanded = false; action() }
                    )
                }
                onMoveClick?.let { action ->
                    DropdownMenuItem(
                        text = { Text(t("Move to folder", "نقل إلى مجلد")) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                        onClick = { expanded = false; action() }
                    )
                }
                onFavoriteClick?.let { action ->
                    DropdownMenuItem(
                        text = { Text(if (doc.isFavorite) t("Remove from favorites", "إزالة من المفضلة") else t("Add to favorites", "إضافة إلى المفضلة")) },
                        leadingIcon = { Icon(if (doc.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null, tint = GoldBase) },
                        onClick = { expanded = false; action() }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(t("Move to Trash", "نقل إلى سلة المحذوفات"), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false; onDeleteClick() }
                )
            }
        }
    }

    if (isCompactListView) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(
                width = if (isSelected) 1.5.dp else 0.75.dp,
                color = if (isSelected) GoldBase else MaterialTheme.colorScheme.outline
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (!doc.thumbnailPath.isNullOrEmpty() && File(doc.thumbnailPath!!).exists()) {
                        AsyncImage(
                            model = File(doc.thumbnailPath!!),
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HighlightedText(
                            text = doc.title,
                            query = searchQuery,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (doc.isFavorite) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = GoldBase, modifier = Modifier.size(14.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isArabic) "${doc.pageCount} ص • $metaText" else "${doc.pageCount} Pages • $metaText",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (catLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = EmeraldContainerDark.copy(alpha = 0.8f),
                            border = BorderStroke(0.8.dp, Emerald400.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = catLabel,
                                color = EmeraldLight,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    ActionsMenuButton()
                }
            }
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 0.75.dp,
            color = if (isSelected) GoldBase else MaterialTheme.colorScheme.outline
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    if (!doc.thumbnailPath.isNullOrEmpty() && File(doc.thumbnailPath!!).exists()) {
                        AsyncImage(
                            model = File(doc.thumbnailPath!!),
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(36.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                    if (doc.isFavorite && !selectionMode) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = t("Favorite", "مفضلة"),
                            tint = GoldBase,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                .padding(3.dp)
                                .size(14.dp)
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.62f)
                    ) {
                        Text(
                            text = if (isArabic) "${doc.pageCount} ص" else "${doc.pageCount} Pages",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    if (catLabel != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                            shape = RoundedCornerShape(50),
                            color = EmeraldContainerDark.copy(alpha = 0.8f),
                            border = BorderStroke(0.8.dp, Emerald400.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = catLabel,
                                color = EmeraldLight,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        HighlightedText(
                            text = doc.title,
                            query = searchQuery,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        if (doc.tagsCsv.isNotBlank()) {
                            HighlightedText(
                                text = doc.tagsCsv,
                                query = searchQuery,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        } else {
                            Text(
                                text = metaText,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!selectionMode) {
                        ActionsMenuButton()
                    }
                }
            }

            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
