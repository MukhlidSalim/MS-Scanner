import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// ---------------------------------------------------------------------------- versioning
// One source of truth for CI and local builds. CI passes VERSION_CODE / VERSION_NAME explicitly
// (see .github/workflows/build-and-release.yml). The +1000 offset keeps every new build above the
// versionCodes produced by the old workflows, so installed users can always update.
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: ciRunNumber?.plus(1000) ?: 1
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.1.${ciRunNumber ?: 0}"

// Repository used by the in-app updater (GitHub Releases). CI sets GITHUB_REPOSITORY automatically.
val updateRepo: String = System.getenv("UPDATE_REPO")?.takeIf { it.isNotBlank() }
  ?: System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() }
  ?: "MukhlidSalim/MS-Scanner"

// ---------------------------------------------------------------------------- release signing
// Values come from environment variables (CI) or from a local, git-ignored keystore.properties:
//   storeFile=/absolute/path/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Without them the release APK is produced UNSIGNED (never silently debug-signed). The CI release
// job refuses to publish in that case.
val keystoreProperties = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
  System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).isFile &&
  releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
  namespace = "com.example"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.aistudio.docscan.pro"
    minSdk = 24
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    // Real phones (arm) + x86_64 emulator. Drops 32-bit x86 native code (Tesseract, ML Kit) from the APK.
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
  }
  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(releaseStoreFile!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
        enableV1Signing = true
        enableV2Signing = true
      }
    }
  }
  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
    }
    debug {
      // Debug builds install side by side with the release app and never receive release updates.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      // A debug build must never download/install the release APK (different package): updater disabled.
      buildConfigField("String", "UPDATE_REPO", "\"\"")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // ✅ يُخبر Room بمكان حفظ ملفات الـ schema للتحقق من صحة الـ Migrations وقت التجميع
  ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  packaging {
    resources {
      excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
  }
}
dependencies {
  implementation("androidx.print:print:1.0.0")
  implementation("androidx.appcompat:appcompat:1.6.1")
  // Background OCR / classification / suggested title (DocumentAnalysisWorker)
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  // Used directly by GitHubUpdateManager / TessDataManager: declared explicitly instead of relying on Coil's transitive copy.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.mlkit.text.recognition)
  // Google ML Kit Document Scanner (primary capture engine; UI + models delivered by Google Play services, ~300 KB).
  implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
  // ModuleInstallClient: checks / installs the scanner module on first use (GoogleDocumentScanner.start).
  implementation("com.google.android.gms:play-services-base:18.5.0")
  // On-device Arabic OCR (ML Kit has no Arabic model). Model file: TessDataManager (asset or one-time download).
  implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
  // Password-protected (encrypted) PDF export.
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  // Document border detection engine (Canny/contours/cornerSubPix via native JNI). AAR from Maven
  // Central since 4.9.0 — no NDK, no manual SDK import, no jniLibs wiring required.
  implementation("org.opencv:opencv:4.11.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
