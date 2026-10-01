package com.pedro.heartratewatch.mobile

import com.google.android.gms.wearable.DataMap
import com.pedro.heartratewatch.shared.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the watch's live run state (see ExerciseSessionService.pushLiveStatsToPhone
 * on :wear, pushed over LIVE_RUN_SYNC), mirrored onto the phone's home screen -- mainly so you can
 * glance at the phone to confirm the watch is still actually tracking (e.g. with its own screen
 * off) without having to wake the watch to check. Ephemeral/in-memory only: there's nothing to
 * restore across a phone app restart, since the watch keeps re-pushing throughout the whole
 * session regardless of whether the phone app happens to be open to see it.
 */
object LiveRunState {

    data class Snapshot(
        val isActive: Boolean = false,
        val activityType: ActivityType = ActivityType.RUN,
        val currentBpm: Int? = null,
        val distanceMeters: Float = 0f,
        val currentPaceSecPerKm: Int? = null
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun applyFrom(map: DataMap) {
        _state.value = Snapshot(
            isActive = map.getBoolean("is_active"),
            activityType = map.getString("activity_type")?.let {
                runCatching { ActivityType.valueOf(it) }.getOrNull()
            } ?: ActivityType.RUN,
            currentBpm = map.getInt("current_bpm").takeIf { it > 0 },
            distanceMeters = map.getFloat("distance_meters"),
            currentPaceSecPerKm = map.getInt("current_pace_sec_per_km").takeIf { it > 0 }
        )
    }
}
