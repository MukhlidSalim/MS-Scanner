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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DocumentEntity
import com.example.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    highlightColor: Color = MaterialTheme.colorScheme.primaryContainer,
    modifier: Modifier = Modifier
) {
    if (query.isBlank()) {
        Text(text = text, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }

    val annotatedString = buildAnnotatedString {
        var lastIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        var index = lowerText.indexOf(lowerQuery)
        while (index != -1) {
            append(text.substring(lastIndex, index))
            withStyle(style = SpanStyle(background = highlightColor)) {
                append(text.substring(index, index + query.length))
            }
            lastIndex = index + query.length
            index = lowerText.indexOf(lowerQuery, lastIndex)
        }
        append(text.substring(lastIndex))
    }

    Text(text = annotatedString, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Document card. Every menu entry is wired to its own action (previously "Share" performed "Export",
 * and Export / Rename / Delete were not connected at all from the Home screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridItem(
    doc: DocumentEntity,
    searchQuery: String,
    isSelected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
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
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))),
                                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                            )
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic) "${doc.pageCount} صفحة" else "${doc.pageCount} p",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
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
                                text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(doc.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!selectionMode) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridItem(
    folderName: String,
    documentCount: Int,
    onClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            ),
            width = 1.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.txt_rename_folder)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                expanded = false
                                onRenameClick()
                            }
                        )
                    }
                }
            }

            Column {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$documentCount documents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
