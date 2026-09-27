import re
with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    content = f.read()

# I will replace the corrupted signingConfigs entirely.
old_block = """  signingConfigs {
    /debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }"""

new_block = """  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }"""

content = content.replace(old_block, new_block)

with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(content)
