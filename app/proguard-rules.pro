# ---------------------------------------------------------------------------
# MS Scanner - R8 rules for the minified release build.
# ---------------------------------------------------------------------------

# Readable crash stack traces (line numbers kept, source file name hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

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

# ---------------------------------------------------------------------------
# Google ML Kit Document Scanner / Google Play services  (ROOT CAUSE of the scanner failure)
# ---------------------------------------------------------------------------
# AGP 9.x enables R8 FULL MODE by default. The play-services-mlkit-* optional-module libraries do not
# ship complete consumer keep rules: their internal clients are created through component registrars and
# lazy instance maps resolved by reflection. In full mode R8 strips them / rewrites them as "always null",
# so GmsDocumentScanning.getClient() / getStartScanIntent() throw a NullPointerException (without message)
# ONLY in the minified release APK; debug builds work.
# Same defect on the sibling API: https://github.com/googlesamples/mlkit/issues/1018
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.moduleinstall.** { *; }
-keep class com.google.android.gms.common.api.internal.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# OpenCV (document detector): Java wrappers are bound to native code by name (JNI) and the AAR ships
# no consumer rules for R8 full mode.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
