with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    content = f.read()
content = content.replace("dependencies {", "dependencies {\n    implementation(\"androidx.print:print:1.0.0\")")
with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(content)
