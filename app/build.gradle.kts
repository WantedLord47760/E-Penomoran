plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.jetgpt.stockity"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jetgpt.stockity"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Retained for the legacy Spaceman source files in this repository.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
