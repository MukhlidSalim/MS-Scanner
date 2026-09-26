package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.PageEntity
import com.example.engine.cv.ImageProcessor
import com.example.engine.cv.MergeFitMode
import com.example.engine.cv.MergeGridLayout
import com.example.ui.theme.CyanScan
import com.example.ui.theme.Emerald400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergePagesDialog(
    allPages: List<PageEntity>,
    initialSelectedPageIds: List<Long>,
    onDismiss: () -> Unit,
    onMergeCompleted: (mergedImagePath: String, selectedPageIds: List<Long>, replaceSelected: Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"

    // Multi-selected page paths and IDs in selection order
    val selectedPageIds = remember {
        mutableStateListOf<Long>().apply {
            if (initialSelectedPageIds.isNotEmpty()) {
                addAll(initialSelectedPageIds)
            } else if (allPages.isNotEmpty()) {
                add(allPages.first().id)
            }
        }
    }

    // Additional imported image paths from gallery
    val extraImagePaths = remember { mutableStateListOf<String>() }

    // Merge settings
    var selectedLayout by remember { mutableStateOf(MergeGridLayout.AUTO) }
    var imageScale by remember { mutableStateOf(0.90f) } // 0.50f to 1.0f
    var spacingDp by remember { mutableStateOf(20f) } // 8 to 48 dp
    var cornerRadiusDp by remember { mutableStateOf(12f) } // 0 to 28 dp
    var hasBorder by remember { mutableStateOf(true) }
    var fitMode by remember { mutableStateOf(MergeFitMode.FIT) }
    var selectedBgColor by remember { mutableStateOf(Color.White) }
    var replaceOriginalPages by remember { mutableStateOf(false) }

    var isMerging by remember { mutableStateOf(false) }

    // External photo picker
    val extraPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                for (uri in uris) {
                    try {
                        val stream = context.contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) {
                            val savedPath = ImageProcessor.saveBitmapToFile(context, bmp, "merge_extra_")
                            bmp.recycle()
                            extraImagePaths.add(savedPath)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // Gather effective list of image paths for merge
    val activeImagePaths = remember(selectedPageIds.toList(), extraImagePaths.toList()) {
        val paths = mutableListOf<String>()
        selectedPageIds.forEach { id ->
            val page = allPages.find { it.id == id }
            if (page != null) {
                paths.add(page.processedImagePath)
            }
        }
        paths.addAll(extraImagePaths)
        paths
    }

    Dialog(
        onDismissRequest = { if (!isMerging) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (isArabic) "دمج الصور في صفحة واحدة" else "Merge Images into One Page",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isArabic)
                                    "${activeImagePaths.size} صور محددة"
                                else
                                    "${activeImagePaths.size} images selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!isMerging) onDismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                if (activeImagePaths.size < 2) {
                                    Toast.makeText(
                                        context,
                                        if (isArabic) "يرجى تحديد صورتين على الأقل للدمج" else "Please select at least 2 images to merge",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
                                isMerging = true
                                coroutineScope.launch {
                                    try {
                                        val mergedPath = ImageProcessor.createMultiImageGridCollage(
                                            context = context,
                                            imagePaths = activeImagePaths,
                                            layout = selectedLayout,
                                            imageScale = imageScale,
                                            spacingPx = spacingDp * 1.5f,
                                            cornerRadiusPx = cornerRadiusDp * 1.5f,
                                            hasBorder = hasBorder,
                                            backgroundColor = selectedBgColor.toArgb(),
                                            fitMode = fitMode,
                                            outPrefix = "merged_page_"
                                        )
                                        if (mergedPath.isNotEmpty()) {
                                            onMergeCompleted(mergedPath, selectedPageIds.toList(), replaceOriginalPages)
                                        } else {
                                            Toast.makeText(context, if (isArabic) "فشل دمج الصور" else "Failed to merge images", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(context, if (isArabic) "حدث خطأ أثناء الدمج" else "Error during merge", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isMerging = false
                                    }
                                }
                            },
                            enabled = !isMerging && activeImagePaths.size >= 2,
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            if (isMerging) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isArabic) "جاري الدمج…" else "Merging…", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isArabic) "تطبيق الدمج" else "Apply Merge", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SECTION 1: Page Selection Strip
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isArabic) "1. اختر الصور المراد دمجها" else "1. Select Images to Merge",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = {
                                        extraPhotoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isArabic) "+ من المعرض" else "+ Gallery", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Pages from current document
                                itemsIndexed(allPages) { index, page ->
                                    val isSelected = selectedPageIds.contains(page.id)
                                    val selectionOrder = selectedPageIds.indexOf(page.id) + 1

                                    Box(
                                        modifier = Modifier
                                            .size(76.dp, 102.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) Emerald400 else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                if (isSelected) {
                                                    selectedPageIds.remove(page.id)
                                                } else {
                                                    selectedPageIds.add(page.id)
                                                }
                                            }
                                    ) {
                                        AsyncImage(
                                            model = File(page.processedImagePath),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Badge / Check
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(5.dp),
                                            shape = CircleShape,
                                            color = if (isSelected) Emerald400 else Color.Black.copy(alpha = 0.5f)
                                        ) {
                                            if (isSelected) {
                                                Text(
                                                    text = "$selectionOrder",
                                                    color = Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp).padding(2.dp)
                                                )
                                            }
                                        }

                                        // Page label
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp),
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color.Black.copy(alpha = 0.7f)
                                        ) {
                                            Text(
                                                text = if (isArabic) "ص ${index + 1}" else "P ${index + 1}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                // Extra gallery images
                                itemsIndexed(extraImagePaths) { index, extraPath ->
                                    Box(
                                        modifier = Modifier
                                            .size(76.dp, 102.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(2.dp, CyanScan, RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = File(extraPath),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        IconButton(
                                            onClick = { extraImagePaths.removeAt(index) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(Color.Red, CircleShape)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2: Multi-Grid Layout Selection (تنسيق شبكي متعدد)
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (isArabic) "2. طريقة التنسيق الشبكي" else "2. Multi-Grid Layout Style",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MergeGridLayout.values().forEach { layout ->
                                    val isSelected = selectedLayout == layout
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedLayout = layout },
                                        label = {
                                            Text(
                                                text = if (isArabic) layout.titleAr else layout.titleEn,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (layout) {
                                                    MergeGridLayout.AUTO -> Icons.Default.AutoAwesome
                                                    MergeGridLayout.VERTICAL_2 -> Icons.Default.TableRows
                                                    MergeGridLayout.HORIZONTAL_2 -> Icons.Default.ViewColumn
                                                    MergeGridLayout.GRID_4 -> Icons.Default.GridView
                                                    MergeGridLayout.VERTICAL_3 -> Icons.Default.FormatLineSpacing
                                                    MergeGridLayout.HORIZONTAL_3 -> Icons.Default.ViewWeek
                                                    MergeGridLayout.GRID_6 -> Icons.Default.Dashboard
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Emerald400,
                                            selectedLabelColor = Color.Black,
                                            selectedLeadingIconColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // SECTION 3: Image Sizing & Spacing Controls (إمكانية تغيير حجم الصورة والتباعد)
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (isArabic) "3. ضبط الحجم والأبعاد والتباعد" else "3. Sizing, Spacing & Padding",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Image Scale Slider (حجم الصورة داخل الصفحة)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isArabic) "حجم الصورة داخل الإطار:" else "Image Scale:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${(imageScale * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Emerald400
                                )
                            }
                            Slider(
                                value = imageScale,
                                onValueChange = { imageScale = it },
                                valueRange = 0.50f..1.0f,
                                steps = 10,
                                colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Spacing Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isArabic) "المسافة والتباعد بين الصور:" else "Padding & Spacing:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${spacingDp.roundToInt()} dp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Emerald400
                                )
                            }
                            Slider(
                                value = spacingDp,
                                onValueChange = { spacingDp = it },
                                valueRange = 8f..48f,
                                steps = 8,
                                colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Corner Radius Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isArabic) "انحناء زوايا الصور:" else "Corner Radius:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${cornerRadiusDp.roundToInt()} dp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Emerald400
                                )
                            }
                            Slider(
                                value = cornerRadiusDp,
                                onValueChange = { cornerRadiusDp = it },
                                valueRange = 0f..28f,
                                steps = 7,
                                colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Fit Mode & Border Toggles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = if (isArabic) "وضع ملاءمة الصورة:" else "Image Fit Mode:")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = fitMode == MergeFitMode.FIT,
                                        onClick = { fitMode = MergeFitMode.FIT },
                                        label = { Text(if (isArabic) "احتواء كامل" else "Fit") },
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    FilterChip(
                                        selected = fitMode == MergeFitMode.FILL,
                                        onClick = { fitMode = MergeFitMode.FILL },
                                        label = { Text(if (isArabic) "ملء الإطار" else "Fill") },
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Page Background Color
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = if (isArabic) "لون خلفية الصفحة:" else "Page Background:")
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val bgColors = listOf(
                                        Color.White to "White",
                                        Color(0xFFF3F4F6) to "Gray",
                                        Color(0xFF18181B) to "Dark"
                                    )
                                    bgColors.forEach { (col, _) ->
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(col)
                                                .border(
                                                    width = if (selectedBgColor == col) 2.5.dp else 1.dp,
                                                    color = if (selectedBgColor == col) Emerald400 else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { selectedBgColor = col }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 4: Live Interactive Preview (معاينة حية للصفحة الناتجة)
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (isArabic) "4. معاينة الصفحة الناتجة (A4 Sheet)" else "4. Live Page Preview",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Interactive A4 simulation canvas
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.707f) // Standard A4 aspect ratio 1:1.414
                                    .shadow(6.dp, RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(selectedBgColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .padding((spacingDp * 0.7f).dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (activeImagePaths.isEmpty()) {
                                    Text(
                                        text = if (isArabic) "اختر صوراً لعرض المعاينة" else "Select images to preview",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    val count = activeImagePaths.size
                                    val (cols, rows) = when (selectedLayout) {
                                        MergeGridLayout.AUTO -> {
                                            when {
                                                count <= 2 -> Pair(1, 2)
                                                count == 3 -> Pair(1, 3)
                                                count == 4 -> Pair(2, 2)
                                                count in 5..6 -> Pair(2, 3)
                                                else -> Pair(2, ceil(count / 2.0).toInt())
                                            }
                                        }
                                        MergeGridLayout.VERTICAL_2 -> Pair(1, 2)
                                        MergeGridLayout.HORIZONTAL_2 -> Pair(2, 1)
                                        MergeGridLayout.GRID_4 -> Pair(2, 2)
                                        MergeGridLayout.VERTICAL_3 -> Pair(1, 3)
                                        MergeGridLayout.HORIZONTAL_3 -> Pair(3, 1)
                                        MergeGridLayout.GRID_6 -> Pair(2, 3)
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy((spacingDp * 0.5f).dp)
                                    ) {
                                        for (r in 0 until rows) {
                                            Row(
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy((spacingDp * 0.5f).dp)
                                            ) {
                                                for (c in 0 until cols) {
                                                    val index = r * cols + c
                                                    if (index < activeImagePaths.size) {
                                                        val path = activeImagePaths[index]
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .fillMaxHeight(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxSize(fraction = imageScale)
                                                                    .clip(RoundedCornerShape((cornerRadiusDp * 0.6f).dp))
                                                                    .background(Color.LightGray.copy(alpha = 0.2f))
                                                                    .then(
                                                                        if (hasBorder) Modifier.border(
                                                                            1.dp,
                                                                            Color.Gray.copy(alpha = 0.4f),
                                                                            RoundedCornerShape((cornerRadiusDp * 0.6f).dp)
                                                                        ) else Modifier
                                                                    )
                                                            ) {
                                                                AsyncImage(
                                                                    model = File(path),
                                                                    contentDescription = null,
                                                                    contentScale = if (fitMode == MergeFitMode.FIT) ContentScale.Fit else ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 5: Output Mode
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isArabic) "استبدال الصفحات المحددة" else "Replace Selected Pages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isArabic)
                                        "سيتم استبدال الصور المحددة بالصفحة المدمجة الجديدة بدلاً من إضافتها كصفحة إضافية"
                                    else
                                        "Replaces original pages with the new merged page instead of adding at end",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = replaceOriginalPages,
                                onCheckedChange = { replaceOriginalPages = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Emerald400)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
