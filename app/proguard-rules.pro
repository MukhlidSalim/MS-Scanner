# ---------------------------------------------------------------------------
# MS Scanner - R8 rules for the minified release build.
# Libraries (Room, WorkManager, CameraX, ML Kit, OkHttp, Coil, Compose) ship their own consumer rules;
# only what the app itself needs is listed here.
# ---------------------------------------------------------------------------

# Readable crash stack traces (line numbers kept, source file name hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum values are persisted by NAME (filterType, lockType, page size, compression, detection status,
# OCR language) and parsed back with valueOf(): keep names and valueOf/values.
-keepclassmembers,allowoptimization enum com.example.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# WorkManager instantiates workers by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room entities are written/read by generated code; keep field names stable for migrations/debugging.
-keep class com.example.data.model.** { *; }

# OkHttp / Okio optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tesseract4Android: Java classes are bound from native code (JNI) by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# PdfBox-Android (encrypted PDF export): loads resources/classes reflectively; optional JPEG2000 codec absent.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**
