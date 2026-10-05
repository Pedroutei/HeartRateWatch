package com.pedro.heartratewatch.wear

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

/**
 * Cumulative GPS distance for a run, computed here rather than by Health Services (whose pipeline
 * stalls with the screen off on this watch -- see HeartRateSensorSource). A location-typed
 * foreground service (see the manifest) is what lets the fused provider keep delivering fixes with
 * the screen off.
 *
 * Raw GPS jitters even when standing still, so naively summing fix-to-fix distance would
 * accumulate fake distance. Instead each fix is measured against the last *counted* fix (the
 * anchor), and only counts once it's clearly farther than the noise: slow real movement still adds
 * up (the anchor stays put until it's passed), but jitter around one spot never does. [onDistance]
 * fires for every usable fix, even ones that don't add distance, so pace smoothing keeps getting
 * regular samples.
 */
class LocationDistanceTracker(
    context: Context,
    private val onDistance: (Float) -> Unit
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private var anchor: Location? = null
    private var totalMeters = 0f

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::accept)
        }
    }

    /** Throws SecurityException if location permission is missing. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun accept(fix: Location) {
        if (fix.hasAccuracy() && fix.accuracy > MAX_ACCURACY_METERS) return

        val from = anchor
        if (from == null) {
            anchor = fix
            onDistance(totalMeters)
            return
        }

        val seconds = (fix.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000_000.0
        if (seconds <= 0.0) return
        val delta = from.distanceTo(fix)

        // Faster than any human can run (same ceiling as MIN_PLAUSIBLE_PACE_SEC_PER_KM): a bad fix,
        // not real movement. Dropped without moving the anchor, so the next good fix is measured
        // against the last trusted position over the real elapsed time.
        if (delta / seconds > MAX_SPEED_METERS_PER_SECOND) return

        val noiseFloor = max(MIN_STEP_METERS, if (fix.hasAccuracy()) fix.accuracy * 0.5f else 0f)
        if (delta >= noiseFloor) {
            totalMeters += delta
            anchor = fix
        }
        onDistance(totalMeters)
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MAX_ACCURACY_METERS = 25f
        const val MIN_STEP_METERS = 3f
        const val MAX_SPEED_METERS_PER_SECOND = 10f
    }
}
