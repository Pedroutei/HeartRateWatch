package com.pedro.heartratewatch.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Everything a session needs before ExerciseSessionService can actually start. Shared between
 * MainActivity's own "Grant access" screen and TileActionActivity, which has to make the same
 * check before trying to start a session from a Tile tap -- otherwise starting the sensors throws a
 * SecurityException the tile has no way to show the user. */
fun requiredWearPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 36) {
        add("android.permission.health.READ_HEART_RATE")
    } else {
        add(Manifest.permission.BODY_SENSORS)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun hasRequiredWearPermissions(context: Context): Boolean =
    requiredWearPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
