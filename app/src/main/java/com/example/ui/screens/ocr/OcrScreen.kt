package com.example.ui.screens.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.ocr.OcrLanguage
import com.example.ui.theme.Emerald400
import com.example.ui.viewmodel.EditSessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    docId: Long,
    pageId: Long,
    viewModel: EditSessionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en

    val uiState by viewModel.uiState.collectAsState()
    val ocrResult = uiState.ocrResult
    var editableFullText by remember(ocrResult?.fullText) { mutableStateOf(ocrResult?.fullText.orEmpty()) }
    var isTextModified by remember(ocrResult?.fullText) { mutableStateOf(false) }

    LaunchedEffect(pageId) {
        viewModel.runOcrInBackground(pageId, force = false)
    }

    var selectedTab by remember { mutableStateOf(0) } // 0: Structured Data, 1: Full Text
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.example.ui.util.UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is com.example.ui.util.UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.txt_ocr_document), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.runOcrInBackground(pageId, force = true) },
                        modifier = Modifier.testTag("ocr_retry_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.desc_re_analyze))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Language selector bar for instant re-analysis
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(t("OCR Language:", "لغة القراءة:"), style = MaterialTheme.typography.labelMedium)
                val langs = listOf(
                    OcrLanguage.AUTO to t("Arabic + English", "عربي + إنجليزي"),
                    OcrLanguage.ARABIC to t("Arabic", "عربي فقط"),
                    OcrLanguage.ENGLISH to t("English", "إنجليزي فقط")
                )
                langs.forEach { (lang, label) ->
                    FilterChip(
                        selected = uiState.ocrLanguage == lang,
                        onClick = {
                            if (uiState.ocrLanguage != lang && !uiState.isOcrLoading) {
                                viewModel.setOcrLanguage(lang)
                                viewModel.runOcrInBackground(pageId, force = true)
                            }
                        },
                        label = { Text(label, fontSize = 12.sp) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = stringResource(R.string.txt_structured_data),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = stringResource(R.string.txt_full_ocr_text),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }

            if (uiState.isOcrLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = t("Analyzing document text…", "جاري تحليل النص واستخراج البيانات…"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (ocrResult != null) {
                if (selectedTab == 0) {
                    // Structured Data View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Smart Title Banner
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = t("Suggested Title & Category", "العنوان والتصنيف المقترح ذكياً"),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = ocrResult.suggestedTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                val catName = if (isArabic) ocrResult.detectedCategory.displayNameAr else ocrResult.detectedCategory.displayNameEn
                                val confPct = (ocrResult.confidence * 100).toInt()
                                Text(
                                    text = if (isArabic) "التصنيف: $catName • الدقة: $confPct%" else "Category: $catName • Confidence: $confPct%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = {
                                        viewModel.updateDocumentMetadata(
                                            docId = docId,
                                            title = ocrResult.suggestedTitle,
                                            category = ocrResult.detectedCategory.name
                                        )
                                        Toast.makeText(
                                            context,
                                            t("Title applied!", "تم تطبيق العنوان المقترح!"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("apply_suggested_title_btn")
                                ) {
                                    Text(stringResource(R.string.txt_apply_title_to_document), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Extracted Fields List
                        Text(
                            text = t("Key Extracted Fields", "الحقول والبيانات المستخرجة"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (ocrResult.fields.isNotEmpty()) {
                            ocrResult.fields.forEach { field ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isArabic) field.labelAr else "${field.labelEn} (${field.labelAr})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = field.value,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        IconButton(onClick = {
                                            copyToClipboard(context, field.value)
                                            Toast.makeText(context, t("Copied to clipboard", "تم النسخ إلى الحافظة"), Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = stringResource(R.string.desc_copy),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = t("No specific structured entities detected.", "لم يتم العثور على حقول مهيكلة محددة في هذه الصفحة."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Full OCR Text View (Editable + Saveable)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isTextModified) {
                                Button(
                                    onClick = {
                                        viewModel.saveEditedOcrText(pageId, editableFullText)
                                        isTextModified = false
                                        Toast.makeText(context, t("Edited text saved!", "تم حفظ النص المعدل!"), Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = androidx.compose.ui.graphics.Color.Black)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("Save Edits", "حفظ التعديلات"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else {
                                Text(
                                    t("Tap text below to edit before exporting", "يمكنك تعديل النص وتصحيحه قبل التصدير"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    copyToClipboard(context, editableFullText)
                                    Toast.makeText(context, t("Full text copied!", "تم نسخ النص بالكامل!"), Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.txt_copy_all), fontWeight = FontWeight.SemiBold)
                                }

                                TextButton(onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, editableFullText)
                                    }
                                    context.startActivity(Intent.createChooser(intent, t("Share OCR Text", "مشاركة النص المستخرج")))
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.txt_share), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = editableFullText,
                            onValueChange = {
                                editableFullText = it
                                isTextModified = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.txt_analysis_failed__please_tr), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { viewModel.runOcrInBackground(pageId, force = true) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.txt_retry_analysis), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(context.getString(R.string.txt_docscan_ocr), text)
    clipboard.setPrimaryClip(clip)
}
