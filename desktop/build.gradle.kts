import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.0"
    id("org.jetbrains.compose") version "1.5.11"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "com.example.audio_stream_app"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    // fat jar 同時打包 Intel 與 Apple Silicon 兩套 macOS 原生庫，同一 jar 可在兩種架構運行
    implementation(compose.desktop.macos_x64)
    implementation(compose.desktop.macos_arm64)
    implementation(compose.material3)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.example.audio_stream_app.desktop.MainKt")
}

compose.desktop {
    application {
        mainClass = "com.example.audio_stream_app.desktop.MainKt"
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "AudioSuitZulu",
            "Implementation-Version" to project.version
        )
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("AudioSuitZulu-desktop")
    archiveClassifier.set("all")
    mergeServiceFiles()
    // 去除已簽名依賴的簽名檔，避免 fat jar 內簽名校驗失敗
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}
