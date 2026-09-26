package com.example.ui.screens.idcard

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.cv.CardArrangement
import com.example.engine.cv.ImageProcessor
import com.example.ui.theme.Emerald400
import com.example.ui.theme.StudioCanvasBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardMergerScreen(
    frontImagePath: String,
    backImagePath: String,
    isPassportMode: Boolean = false,
    onMerged: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isArabic = context.resources.configuration.locales[0].language == "ar"

    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Card scale & arrangement controls (خط تكبير وتصغير وطريقة الترتيب)
    var cardScale by remember { mutableFloatStateOf(0.85f) }
    var arrangement by remember { mutableStateOf(CardArrangement.TOP_BOTTOM) }
    var isSwapped by remember { mutableStateOf(false) }
    var spacingFactor by remember { mutableFloatStateOf(1.0f) }
    var hasBorder by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    val hasTwoCards = remember(frontImagePath, backImagePath) {
        backImagePath.isNotBlank() && backImagePath != frontImagePath
    }

    LaunchedEffect(frontImagePath, backImagePath) {
        withContext(Dispatchers.IO) {
            frontBitmap = ImageProcessor.loadBitmapFromFile(frontImagePath, 1600)
            if (hasTwoCards) {
                backBitmap = ImageProcessor.loadBitmapFromFile(backImagePath, 1600)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isPassportMode) {
                            if (isArabic) "تنسيق وحجم جواز السفر (A4)" else "Passport Layout & Size (A4)"
                        } else {
                            if (isArabic) "دمج وتنسيق حجم البطاقة (A4)" else "ID Card Layout & Size (A4)"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            isSaving = true
                            coroutineScope.launch {
                                val outPrefix = if (isPassportMode) "passport_proc_" else "idcard_proc_"
                                val mergedPath = ImageProcessor.createIdCardCollage(
                                    context = context,
                                    frontPath = frontImagePath,
                                    backPath = if (hasTwoCards) backImagePath else "",
                                    outPrefix = outPrefix,
                                    isSideBySide = arrangement == CardArrangement.SIDE_BY_SIDE,
                                    scale = cardScale,
                                    arrangement = arrangement,
                                    swapOrder = isSwapped,
                                    isPassport = isPassportMode,
                                    spacingFactor = spacingFactor,
                                    hasBorder = hasBorder
                                )
                                isSaving = false
                                onMerged(mergedPath)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Color.Black),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.txt_save), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 14.dp,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Scale Slider Header (خط تكبير وتصغير حجم البطاقات بالصورة)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.ZoomIn,
                                contentDescription = null,
                                tint = Emerald400,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isPassportMode) {
                                    if (isArabic) "خط تكبير وتصغير حجم الجواز بالصفحة:" else "Passport Size on Page (Zoom):"
                                } else {
                                    if (isArabic) "خط تكبير وتصغير حجم البطاقات بالصفحة:" else "Card Size on Page (Zoom):"
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Percentage indicator badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Emerald400.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Emerald400.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = "${(cardScale * 100).toInt()}%",
                                color = Emerald400,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Continuous Slider for scaling cards/passport
                    Slider(
                        value = cardScale,
                        onValueChange = { cardScale = it },
                        valueRange = 0.40f..1.05f,
                        colors = SliderDefaults.colors(
                            thumbColor = Emerald400,
                            activeTrackColor = Emerald400,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    )

                    // Quick Scale Presets (أحجام جاهزة سريعة)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(
                            0.55f to (if (isArabic) "صغير (55%)" else "Small (55%)"),
                            0.70f to (if (isArabic) "متوسط (70%)" else "Medium (70%)"),
                            0.85f to (if (isArabic) "قياسي (85%)" else "Standard (85%)"),
                            1.00f to (if (isArabic) "كبير (100%)" else "Large (100%)")
                        )
                        presets.forEach { (presetVal, label) ->
                            val isSelected = kotlin.math.abs(cardScale - presetVal) < 0.05f
                            FilterChip(
                                selected = isSelected,
                                onClick = { cardScale = presetVal },
                                shape = RoundedCornerShape(16.dp),
                                label = {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 2. Arrangement Controls (طريقة ترتيب البطاقات في الصفحة)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.DashboardCustomize,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isPassportMode) {
                                    if (isArabic) "طريقة ترتيب صفحات الجواز في الصفحة:" else "Passport Arrangement on Page:"
                                } else {
                                    if (isArabic) "طريقة ترتيب البطاقات في الصفحة:" else "Card Arrangement on Page:"
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Swap Order Button
                        if (hasTwoCards) {
                            TextButton(
                                onClick = { isSwapped = !isSwapped },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isArabic) "تبديل الترتيب" else "Swap Order",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Arrangement Selection Chips (Scrollable row)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Top & Bottom (فوق وتحت - عمودي متوازن)
                        FilterChip(
                            selected = arrangement == CardArrangement.TOP_BOTTOM,
                            onClick = { arrangement = CardArrangement.TOP_BOTTOM },
                            leadingIcon = { Icon(Icons.Default.ViewAgenda, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(if (isArabic) "فوق وتحت" else "Top & Bottom", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )

                        // 2. Top of Page (أعلى الصفحة - رسمي)
                        FilterChip(
                            selected = arrangement == CardArrangement.TOP_PAGE,
                            onClick = { arrangement = CardArrangement.TOP_PAGE },
                            leadingIcon = { Icon(Icons.Default.TableRows, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(if (isArabic) "أعلى الصفحة (رسمي)" else "Top of Page", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )

                        // 3. Side by Side (جنباً إلى جنب - أفقي)
                        FilterChip(
                            selected = arrangement == CardArrangement.SIDE_BY_SIDE,
                            onClick = { arrangement = CardArrangement.SIDE_BY_SIDE },
                            leadingIcon = { Icon(Icons.Default.ViewColumn, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(if (isArabic) "جنباً إلى جنب" else "Side by Side", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )

                        // 4. Centered (في المنتصف)
                        FilterChip(
                            selected = arrangement == CardArrangement.CENTERED,
                            onClick = { arrangement = CardArrangement.CENTERED },
                            leadingIcon = { Icon(Icons.Default.VerticalAlignCenter, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(if (isArabic) "في المنتصف" else "Centered", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )

                        // 5. Fit Width (ملء العرض)
                        FilterChip(
                            selected = arrangement == CardArrangement.FIT_PAGE,
                            onClick = { arrangement = CardArrangement.FIT_PAGE },
                            leadingIcon = { Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(if (isArabic) "ملء العرض" else "Fit Width", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )
                    }

                    // 3. Additional controls (تباعد البطاقات وإطار البطاقة)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Card Border Toggle
                        FilterChip(
                            selected = hasBorder,
                            onClick = { hasBorder = !hasBorder },
                            leadingIcon = { Icon(Icons.Default.BorderColor, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            label = { Text(if (isArabic) "إطار البطاقة" else "Card Border", fontSize = 11.sp) }
                        )

                        // Card Spacing Toggle
                        FilterChip(
                            selected = spacingFactor != 1.0f,
                            onClick = {
                                spacingFactor = when {
                                    spacingFactor < 0.8f -> 1.0f
                                    spacingFactor > 1.2f -> 0.5f
                                    else -> 1.6f
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.FormatLineSpacing, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            label = {
                                val spacingName = when {
                                    spacingFactor < 0.8f -> if (isArabic) "متقارب" else "Compact"
                                    spacingFactor > 1.2f -> if (isArabic) "متباعد" else "Spacious"
                                    else -> if (isArabic) "متوازن" else "Normal"
                                }
                                Text(if (isArabic) "تباعد: $spacingName" else "Spacing: $spacingName", fontSize = 11.sp)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(StudioCanvasBg)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val rawFront = frontBitmap
            val rawBack = backBitmap

            if (rawFront != null) {
                val firstBmp = if (isSwapped && rawBack != null) rawBack else rawFront
                val secondBmp = if (isSwapped && rawBack != null) rawFront else rawBack

                val firstRatio = firstBmp.width.toFloat() / firstBmp.height.toFloat().coerceAtLeast(0.1f)
                val secondRatio = secondBmp?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(0.1f) } ?: firstRatio

                val firstLabel = if (isPassportMode) {
                    if (isSwapped) (if (isArabic) "صفحة 2" else "Page 2") else (if (isArabic) "صفحة 1 (البيانات)" else "Page 1 (Bio)")
                } else {
                    if (isSwapped) (if (isArabic) "الوجه الخلفي" else "Back") else (if (isArabic) "الوجه الأمامي" else "Front")
                }

                val secondLabel = if (isPassportMode) {
                    if (isSwapped) (if (isArabic) "صفحة 1 (البيانات)" else "Page 1 (Bio)") else (if (isArabic) "صفحة 2" else "Page 2")
                } else {
                    if (isSwapped) (if (isArabic) "الوجه الأمامي" else "Front") else (if (isArabic) "الوجه الخلفي" else "Back")
                }

                // Interactive A4 Sheet Canvas Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f / 1.414f) // Standard A4 ratio
                        .shadow(16.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val scaleFraction = (cardScale.coerceIn(0.40f, 1.05f) * 0.85f).coerceIn(0.35f, 0.96f)

                    if (secondBmp == null) {
                        // Single Card / Passport preview
                        val singleAlignment = when (arrangement) {
                            CardArrangement.TOP_PAGE -> Alignment.TopCenter
                            else -> Alignment.Center
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = if (arrangement == CardArrangement.TOP_PAGE) 18.dp else 0.dp),
                            contentAlignment = singleAlignment
                        ) {
                            val singleWidthFraction = if (arrangement == CardArrangement.FIT_PAGE) 0.94f else scaleFraction
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(singleWidthFraction)
                                    .aspectRatio(firstRatio)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (hasBorder) Modifier.border(1.dp, Color.LightGray.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                        else Modifier
                                    )
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = firstBmp.asImageBitmap(),
                                    contentDescription = firstLabel,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.60f),
                                    shape = RoundedCornerShape(bottomEnd = 6.dp),
                                    modifier = Modifier.align(Alignment.TopStart)
                                ) {
                                    Text(
                                        text = firstLabel,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else when (arrangement) {
                        CardArrangement.SIDE_BY_SIDE -> {
                            // Side by Side Layout (Interactive Scale)
                            val sideWidthFraction = (cardScale * 0.44f).coerceIn(0.20f, 0.46f)
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Card 1
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(sideWidthFraction)
                                        .aspectRatio(firstRatio)
                                        .clip(RoundedCornerShape(6.dp))
                                        .then(
                                            if (hasBorder) Modifier.border(1.dp, Color.LightGray.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                            else Modifier
                                        )
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = firstBmp.asImageBitmap(),
                                        contentDescription = firstLabel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.60f),
                                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = firstLabel,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width((8 * spacingFactor).dp.coerceIn(4.dp, 24.dp)))

                                // Card 2
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(sideWidthFraction / (1f - sideWidthFraction).coerceAtLeast(0.1f))
                                        .aspectRatio(secondRatio)
                                        .clip(RoundedCornerShape(6.dp))
                                        .then(
                                            if (hasBorder) Modifier.border(1.dp, Color.LightGray.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                            else Modifier
                                        )
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = secondBmp.asImageBitmap(),
                                        contentDescription = secondLabel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.60f),
                                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = secondLabel,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            // Vertical layouts (TOP_BOTTOM, TOP_PAGE, CENTERED, FIT_PAGE)
                            val vertWidthFraction = if (arrangement == CardArrangement.FIT_PAGE) 0.94f else scaleFraction
                            val vertArrangement = when (arrangement) {
                                CardArrangement.CENTERED -> Arrangement.Center
                                CardArrangement.TOP_PAGE -> Arrangement.Top
                                else -> Arrangement.SpaceEvenly
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = if (arrangement == CardArrangement.TOP_PAGE) 12.dp else 0.dp),
                                verticalArrangement = vertArrangement,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Card 1
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(vertWidthFraction)
                                        .aspectRatio(firstRatio)
                                        .clip(RoundedCornerShape(6.dp))
                                        .then(
                                            if (hasBorder) Modifier.border(1.dp, Color.LightGray.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                            else Modifier
                                        )
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = firstBmp.asImageBitmap(),
                                        contentDescription = firstLabel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.60f),
                                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = firstLabel,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                if (arrangement == CardArrangement.CENTERED || arrangement == CardArrangement.TOP_PAGE) {
                                    Spacer(modifier = Modifier.height((12 * spacingFactor).dp.coerceIn(6.dp, 36.dp)))
                                }

                                // Card 2
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(vertWidthFraction)
                                        .aspectRatio(secondRatio)
                                        .clip(RoundedCornerShape(6.dp))
                                        .then(
                                            if (hasBorder) Modifier.border(1.dp, Color.LightGray.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                            else Modifier
                                        )
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = secondBmp.asImageBitmap(),
                                        contentDescription = secondLabel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.60f),
                                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = secondLabel,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(color = Emerald400)
            }
        }
    }
}
