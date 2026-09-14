plugins {
    id("com.android.library")
    // No "org.jetbrains.kotlin.android" here -- AGP 9+ compiles Kotlin itself, see root
    // build.gradle.kts for why.
}

android {
    namespace = "com.pedro.heartratewatch.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
