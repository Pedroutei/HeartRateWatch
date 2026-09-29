package com.pedro.heartratewatch.wear

import android.content.Context
import com.pedro.heartratewatch.shared.TrainingSettings

// Strava's Android app package name -- also declared in AndroidManifest.xml's <queries> block,
// required since API 30 for getLaunchIntentForPackage to see another app at all.
private const val STRAVA_PACKAGE_NAME = "com.strava"

/**
 * Brings Strava to the foreground if the setting's on and it's installed -- shared by
 * MainActivity's own Start button and TileActionActivity's, so starting a workout from the Tile
 * does the same thing starting it from the app does. Must be called from an already-foregrounded
 * context (a click handler, or an activity's onCreate while it's genuinely resumed) so this
 * counts as a user-initiated launch rather than a background one Android would block. There's no
 * Strava API to start recording remotely -- this just opens the app; you still tap Record
 * yourself inside it.
 */
fun launchStravaIfEnabled(context: Context, settings: TrainingSettings) {
    if (!settings.launchStravaOnStart) return
    context.packageManager.getLaunchIntentForPackage(STRAVA_PACKAGE_NAME)?.let { context.startActivity(it) }
}
