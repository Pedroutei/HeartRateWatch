package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        val LOWER_BPM = intPreferencesKey("lower_bpm")
        val UPPER_BPM = intPreferencesKey("upper_bpm")
        val THRESHOLD_MODE = stringPreferencesKey("threshold_mode")
        val LOWER_PERCENT = intPreferencesKey("lower_percent")
        val UPPER_PERCENT = intPreferencesKey("upper_percent")
        val BREAK_SECONDS = intPreferencesKey("break_seconds")
        val USE_GPS = booleanPreferencesKey("use_gps")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val TARGET_METERS = floatPreferencesKey("target_meters")
    }

    val settingsFlow: Flow<TrainingSettings> = context.settingsDataStore.data.map { prefs ->
        TrainingSettings(
            lowerThresholdBpm = prefs[Keys.LOWER_BPM] ?: 100,
            upperThresholdBpm = prefs[Keys.UPPER_BPM] ?: 160,
            thresholdMode = prefs[Keys.THRESHOLD_MODE]?.let {
                runCatching { ThresholdMode.valueOf(it) }.getOrNull()
            } ?: ThresholdMode.BPM,
            lowerThresholdPercent = prefs[Keys.LOWER_PERCENT] ?: 60,
            upperThresholdPercent = prefs[Keys.UPPER_PERCENT] ?: 85,
            breakTimerSeconds = prefs[Keys.BREAK_SECONDS] ?: 30,
            useGpsForDistance = prefs[Keys.USE_GPS] ?: false,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            distanceTargetMeters = prefs[Keys.TARGET_METERS]?.takeIf { it > 0f }
        )
    }

    suspend fun save(settings: TrainingSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LOWER_BPM] = settings.lowerThresholdBpm
            prefs[Keys.UPPER_BPM] = settings.upperThresholdBpm
            prefs[Keys.THRESHOLD_MODE] = settings.thresholdMode.name
            prefs[Keys.LOWER_PERCENT] = settings.lowerThresholdPercent
            prefs[Keys.UPPER_PERCENT] = settings.upperThresholdPercent
            prefs[Keys.BREAK_SECONDS] = settings.breakTimerSeconds
            prefs[Keys.USE_GPS] = settings.useGpsForDistance
            prefs[Keys.VIBRATION] = settings.vibrationEnabled
            prefs[Keys.TARGET_METERS] = settings.distanceTargetMeters ?: -1f
        }
    }
}