package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "training_settings")

/**
 * Local, on-watch copy of [TrainingSettings]. The *editing* source of truth is meant to be the
 * phone companion app (bigger screen, easier input) -- see
 * com.pedro.heartratewatch.mobile.SettingsRepository -- which pushes changes here via
 * [SettingsSyncListenerService]. This store is what [ExerciseSessionService] reads from when a
 * session starts, so the watch can run a session standalone even if the phone briefly drops
 * out of range mid-run.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val HR_ALERTS_ENABLED = booleanPreferencesKey("hr_alerts_enabled")
        val PACE_ALERTS_ENABLED = booleanPreferencesKey("pace_alerts_enabled")
        val LOWER_BPM = intPreferencesKey("lower_bpm")
        val UPPER_BPM = intPreferencesKey("upper_bpm")
        val THRESHOLD_MODE = stringPreferencesKey("threshold_mode")
        val LOWER_PERCENT = intPreferencesKey("lower_percent")
        val UPPER_PERCENT = intPreferencesKey("upper_percent")
        val MANUAL_MAX_HR = intPreferencesKey("manual_max_hr")
        val FASTEST_PACE_SEC_PER_KM = intPreferencesKey("fastest_pace_sec_per_km")
        val SLOWEST_PACE_SEC_PER_KM = intPreferencesKey("slowest_pace_sec_per_km")
        val WARMUP_SECONDS = intPreferencesKey("warmup_seconds")
        val BREAK_SECONDS = intPreferencesKey("break_seconds")
        val TARGET_METERS = floatPreferencesKey("target_meters")
        val PACE_UNIT = stringPreferencesKey("pace_unit")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    }

    val settingsFlow: Flow<TrainingSettings> = context.settingsDataStore.data.map { prefs ->
        TrainingSettings(
            heartRateAlertsEnabled = prefs[Keys.HR_ALERTS_ENABLED] ?: true,
            paceAlertsEnabled = prefs[Keys.PACE_ALERTS_ENABLED] ?: false,
            lowerThresholdBpm = prefs[Keys.LOWER_BPM] ?: 100,
            upperThresholdBpm = prefs[Keys.UPPER_BPM] ?: 160,
            thresholdMode = prefs[Keys.THRESHOLD_MODE]?.let {
                runCatching { ThresholdMode.valueOf(it) }.getOrNull()
            } ?: ThresholdMode.BPM,
            lowerThresholdPercent = prefs[Keys.LOWER_PERCENT] ?: 60,
            upperThresholdPercent = prefs[Keys.UPPER_PERCENT] ?: 85,
            manualMaxHrBpm = prefs[Keys.MANUAL_MAX_HR]?.takeIf { it > 0 },
            fastestPaceSecPerKm = prefs[Keys.FASTEST_PACE_SEC_PER_KM] ?: 240,
            slowestPaceSecPerKm = prefs[Keys.SLOWEST_PACE_SEC_PER_KM] ?: 420,
            warmupSeconds = prefs[Keys.WARMUP_SECONDS] ?: 30,
            breakTimerSeconds = prefs[Keys.BREAK_SECONDS] ?: 30,
            distanceTargetMeters = prefs[Keys.TARGET_METERS]?.takeIf { it > 0f },
            paceUnit = prefs[Keys.PACE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS,
            distanceUnit = prefs[Keys.DISTANCE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS
        )
    }

    suspend fun save(settings: TrainingSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.HR_ALERTS_ENABLED] = settings.heartRateAlertsEnabled
            prefs[Keys.PACE_ALERTS_ENABLED] = settings.paceAlertsEnabled
            prefs[Keys.LOWER_BPM] = settings.lowerThresholdBpm
            prefs[Keys.UPPER_BPM] = settings.upperThresholdBpm
            prefs[Keys.THRESHOLD_MODE] = settings.thresholdMode.name
            prefs[Keys.LOWER_PERCENT] = settings.lowerThresholdPercent
            prefs[Keys.UPPER_PERCENT] = settings.upperThresholdPercent
            prefs[Keys.MANUAL_MAX_HR] = settings.manualMaxHrBpm ?: -1
            prefs[Keys.FASTEST_PACE_SEC_PER_KM] = settings.fastestPaceSecPerKm
            prefs[Keys.SLOWEST_PACE_SEC_PER_KM] = settings.slowestPaceSecPerKm
            prefs[Keys.WARMUP_SECONDS] = settings.warmupSeconds
            prefs[Keys.BREAK_SECONDS] = settings.breakTimerSeconds
            prefs[Keys.TARGET_METERS] = settings.distanceTargetMeters ?: -1f
            prefs[Keys.PACE_UNIT] = settings.paceUnit.name
            prefs[Keys.DISTANCE_UNIT] = settings.distanceUnit.name
        }
    }
}
