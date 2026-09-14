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

    // Health Services (heart rate + distance during an active exercise session)
    implementation("androidx.health:health-services-client:1.1.0-rc02")

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
