plugins {
    id("com.android.application")
    // No "org.jetbrains.kotlin.android" here -- AGP 9+ compiles Kotlin itself, see root
    // build.gradle.kts for why. "plugin.compose" is a different thing (the Compose compiler)
    // and is still needed.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pedro.heartratewatch.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pedro.heartratewatch"
        minSdk = 30 // Wear OS 3 minimum -- matches the Galaxy Watch 4
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // See the matching comment in mobile/build.gradle.kts -- same shared debug.keystore, so
        // local and CI-built debug APKs always sign identically and `adb install -r` works
        // across them.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))

    // Compose for Wear OS
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Fused GPS for run distance (Health Services stalls with the screen off on this watch, so
    // heart rate and distance both come from raw platform sources instead).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Wear OS Data Layer (talking to the phone module)
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // Tile
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("com.google.guava:guava:33.3.1-android")

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}
