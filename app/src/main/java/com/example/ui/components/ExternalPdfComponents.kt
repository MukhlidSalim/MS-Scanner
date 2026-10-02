package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.engine.pdf.DefaultPdfApp
import com.example.ui.theme.Emerald400
import com.example.ui.theme.GoldBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings banner: shows whether MS Scanner is the default PDF app and starts the system flow to make it so.
 * The status is re-read every time the screen resumes (the user comes back from the system dialog / settings).
 */
@Composable
fun DefaultPdfAppBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var status by remember { mutableStateOf(DefaultPdfApp.status(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = DefaultPdfApp.status(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(GoldBase.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PictureAsPdf, null, tint = GoldBase, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Default PDF app", "التطبيق الافتراضي لملفات PDF"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    when (val st = status) {
                        DefaultPdfApp.Status.ThisApp -> t("MS Scanner opens PDF files", "MS Scanner هو التطبيق الافتراضي")
                        DefaultPdfApp.Status.NotSet -> t("Not set — Android asks every time", "غير معيّن — يسأل أندرويد في كل مرة")
                        is DefaultPdfApp.Status.OtherApp ->
                            if (st.label != null) t("Currently: ${st.label}", "حالياً: ${st.label}")
                            else t("Another app is the default", "تطبيق آخر هو الافتراضي")
                    },
                    fontSize = 12.sp,
                    color = if (status == DefaultPdfApp.Status.ThisApp) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status == DefaultPdfApp.Status.ThisApp) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald400)
            } else {
                TextButton(onClick = {
                    val other = status is DefaultPdfApp.Status.OtherApp
                    if (other) {
                        Toast.makeText(
                            context,
                            t(
                                "Open \"Open by default\" and tap \"Clear defaults\", then open any PDF and choose MS Scanner → Always.",
                                "افتح «الفتح افتراضياً» واضغط «مسح الإعدادات الافتراضية»، ثم افتح أي ملف PDF واختر MS Scanner ← دائماً."
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            t("Choose MS Scanner, then \"Always\".", "اختر MS Scanner ثم «دائماً»."),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (!DefaultPdfApp.launchSetAsDefault(context)) {
                        Toast.makeText(context, t("Could not open the system dialog", "تعذر فتح نافذة النظام"), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(t("Set as default", "تعيين افتراضياً"), color = GoldBase, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Tools of the read-only PDF reader (opened from the reader's gold tools button): Sign a page or open the whole
 * file in the editor. Signing uses the existing annotation screen; [onSign] receives the 0-based page index.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderToolsSheet(
    pdfFile: File,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSign: (pageIndex: Int) -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    val pageCount by produceState(initialValue = 0, pdfFile) {
        value = withContext(Dispatchers.IO) {
            runCatching { SafePdfSession(pdfFile).let { s -> s.pageCount.also { s.close() } } }.getOrDefault(0)
        }
    }
    var choosingPage by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(pdfFile.nameWithoutExtension.replace(Regex("^\\d{10,}_"), ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Emerald400)
                    Spacer(Modifier.width(12.dp))
                    Text(t("Preparing the document…", "جاري تجهيز المستند…"))
                }
                return@Column
            }
            if (!choosingPage) {
                ToolRow(Icons.Default.Draw, t("Sign", "توقيع"), t("Add your signature to a page", "أضف توقيعك على صفحة"), GoldBase) {
                    if (pageCount > 1) choosingPage = true else onSign(0)
                }
                ToolRow(Icons.Default.AutoFixHigh, t("Edit", "تعديل"), t("Open in the MS Scanner editor", "فتح في محرر MS Scanner"), Emerald400, onEdit)
                Text(
                    t("Signing saves a copy in your MS Scanner documents; the original file is not changed.",
                        "التوقيع يحفظ نسخة ضمن مستندات MS Scanner؛ الملف الأصلي لا يتغير."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(t("Which page do you want to sign?", "أي صفحة تريد توقيعها؟"), fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until pageCount).toList()) { i ->
                        FilterChip(selected = false, onClick = { onSign(i) }, label = { Text("${i + 1}") })
                    }
                }
                TextButton(onClick = { choosingPage = false }) { Text(t("Back", "رجوع")) }
            }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Asked only when a PDF really needs a password to be OPENED (owner-only protection is removed silently
 * by PdfCompat before this would ever show).
 */
@Composable
fun PdfPasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val context = LocalContext.current
    val isArabic = context.resources.configuration.locales[0].language == "ar"
    fun t(en: String, ar: String) = if (isArabic) ar else en
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        icon = { Icon(Icons.Default.PictureAsPdf, null, tint = GoldBase) },
        title = { Text(t("Protected PDF", "ملف PDF محمي"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("\"$fileName\" needs a password to open.", "يحتاج «$fileName» إلى كلمة مرور لفتحه."), fontSize = 13.sp)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = wrongPassword,
                    label = { Text(t("Password", "كلمة المرور")) },
                    supportingText = if (wrongPassword) ({ Text(t("Wrong password", "كلمة المرور غير صحيحة")) }) else null,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    t("The password is used only to open the file and is not saved.", "تُستخدم كلمة المرور لفتح الملف فقط ولا يتم حفظها."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text(t("Open", "فتح")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "إلغاء")) } }
    )
}
