import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.czn.patch"
    compileSdk = 36
    defaultConfig {
        applicationId = "id.czn.patch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { buildConfig = true }
    // extracted .so files, so the app_process run through Shizuku can System.load() zstd-jni by path
    packaging { jniLibs { useLegacyPackaging = true } }
    // the translation data ships from the release's data/ folder, so a rebuild always bundles the latest
    sourceSets["main"].assets.srcDir("../../data")
    androidResources { ignoreAssetsPatterns += listOf("sets.dat", "glossary.txt", "*.bak", "cache.sebelum-update-*") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        // shortcut: signed with the local debug key; use a real keystore before publishing updates widely
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    testOptions { unitTests.all { it.systemProperties(System.getProperties().filterKeys { k -> k.toString().startsWith("czn.") }.mapKeys { k -> k.key.toString() }); it.maxHeapSize = "3g" } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-6")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
