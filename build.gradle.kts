// Top-level build file. This only DECLARES plugin versions; each module's own
// build.gradle.kts applies the ones it actually needs.
//
// Versions pinned here (Sept 2026, when this was scaffolded):
//   Android Gradle Plugin 9.4.0 -> requires Gradle 9.6+ (see gradle/wrapper/gradle-wrapper.properties)
//   Kotlin 2.1.20 (compose compiler plugin only -- AGP 9+ compiles Kotlin itself now, see below)
// If Android Studio offers an "Upgrade Assistant" prompt when you first open this project,
// it's safe to accept it -- these will drift as new versions ship.
//
// NOTE: there's deliberately no "org.jetbrains.kotlin.android" plugin declared here. As of AGP
// 9.0, Kotlin compilation is built directly into the Android Gradle Plugin, so applying the
// separate kotlin-android plugin on top causes a "Cannot add extension with name 'kotlin'"
// conflict. The "org.jetbrains.kotlin.plugin.compose" plugin below is still needed -- that's
// the Compose *compiler* plugin, a different thing from kotlin-android.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
