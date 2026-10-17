plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.turboio.customai"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // Distinct package: installs alongside the official RayNeo app, never replaces it.
        applicationId = "org.turboio.customai"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.0-memoria"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = false }
    packaging { resources.excludes += "META-INF/*.kotlin_module" }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Pure-Java Opus decoder (BSD-3) for the glasses microphone stream; no NDK required.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // Daily insight schedule (and, in phase 2, the transcription queue).
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Real SQLite (incl. FTS4) for journal tests on the JVM.
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
