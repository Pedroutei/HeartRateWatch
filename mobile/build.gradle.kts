plugins {
    id("com.android.application")
    // No "org.jetbrains.kotlin.android" here -- AGP 9+ compiles Kotlin itself, see root
    // build.gradle.kts for why. "plugin.compose" is a different thing (the Compose compiler)
    // and is still needed.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pedro.heartratewatch.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pedro.heartratewatch"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // Pinned to a debug.keystore checked into the repo root, rather than each machine's
        // (or CI runner's) own auto-generated one -- otherwise every CI build gets a different
        // signature than your local Android Studio builds, and `adb install -r` starts refusing
        // to "update" over a mismatched signature. This is a debug-only key (password "android",
        // same defaults AGP uses for its own auto-generated one), so there's nothing sensitive
        // about committing it.
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

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Wear OS Data Layer (talking to the watch module)
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}
