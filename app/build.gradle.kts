plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jetgpt.spaceman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jetgpt.spaceman.v18"
        minSdk = 23
        targetSdk = 35
        versionCode = 19
        versionName = "1.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
