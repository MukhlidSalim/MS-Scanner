pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Tesseract4Android (Arabic OCR). Restricted to that group only: nothing else is ever resolved from JitPack.
    maven {
      url = uri("https://jitpack.io")
      content { includeGroup("cz.adaptech.tesseract4android") }
    }
  }
}
rootProject.name = "MS Scanner"
include(":app")
