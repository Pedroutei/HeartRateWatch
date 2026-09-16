package com.pedro.heartratewatch.mobile

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "training_settings")

/**
 * Phone-side settings store, and the actual source of truth for editing: the phone has a real
 * keyboard, so thresholds/break length/target distance get entered here and pushed to the watch
 * over the Data Layer. This uses DataClient (not MessageClient) deliberately -- DataClient
 * items are retained and re-synced automatically once both devices reconnect, which matters if
 * the watch is briefly out of Bluetooth range when a setting changes.
 */
class SettingsRepository(private val context: Context) {

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
        val BREAK_SECONDS = intPreferencesKey("break_seconds")
        val USE_GPS = booleanPreferencesKey("use_gps")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val TARGET_METERS = floatPreferencesKey("target_meters")
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
            breakTimerSeconds = prefs[Keys.BREAK_SECONDS] ?: 30,
            useGpsForDistance = prefs[Keys.USE_GPS] ?: false,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            distanceTargetMeters = prefs[Keys.TARGET_METERS]?.takeIf { it > 0f }
        )
    }

    /** Saves locally on the phone AND pushes to the watch over the Data Layer. */
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
            prefs[Keys.BREAK_SECONDS] = settings.breakTimerSeconds
            prefs[Keys.USE_GPS] = settings.useGpsForDistance
            prefs[Keys.VIBRATION] = settings.vibrationEnabled
            prefs[Keys.TARGET_METERS] = settings.distanceTargetMeters ?: -1f
        }
        pushToWatch(settings)
    }

    private fun pushToWatch(settings: TrainingSettings) {
        val request = PutDataMapRequest.create(DataLayerPaths.SETTINGS_SYNC).apply {
            dataMap.putBoolean("hr_alerts_enabled", settings.heartRateAlertsEnabled)
            dataMap.putBoolean("pace_alerts_enabled", settings.paceAlertsEnabled)
            dataMap.putInt("lower_bpm", settings.lowerThresholdBpm)
            dataMap.putInt("upper_bpm", settings.upperThresholdBpm)
            dataMap.putString("threshold_mode", settings.thresholdMode.name)
            dataMap.putInt("lower_percent", settings.lowerThresholdPercent)
            dataMap.putInt("upper_percent", settings.upperThresholdPercent)
            dataMap.putInt("manual_max_hr", settings.manualMaxHrBpm ?: -1)
            dataMap.putInt("fastest_pace_sec_per_km", settings.fastestPaceSecPerKm)
            dataMap.putInt("slowest_pace_sec_per_km", settings.slowestPaceSecPerKm)
            dataMap.putInt("break_seconds", settings.breakTimerSeconds)
            dataMap.putBoolean("use_gps", settings.useGpsForDistance)
            dataMap.putBoolean("vibration_enabled", settings.vibrationEnabled)
            dataMap.putFloat("target_meters", settings.distanceTargetMeters ?: -1f)
            // A field that always changes, so the DataItem is guaranteed to fire a change event
            // even if every visible setting happens to be identical to the last save.
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Log.d("SettingsSync", "Pushing settings to watch: upper_bpm=${settings.upperThresholdBpm}")
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { Log.d("SettingsSync", "putDataItem succeeded: ${it.uri}") }
            .addOnFailureListener { Log.e("SettingsSync", "putDataItem failed", it) }
    }
}
