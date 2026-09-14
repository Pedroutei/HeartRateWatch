package com.pedro.heartratewatch.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Listens for the "/settings/sync" DataItem that the phone's SettingsRepository pushes every
 * time the user changes a setting there, and mirrors it into this watch's own [SettingsStore].
 *
 * This is registered two ways: as a manifest-declared WearableListenerService (this class, so
 * it *can* wake the app from a cold/stopped state), and as a live listener registered from
 * MainActivity while the app is actually open (see MainActivity.dataChangedListener). Testing
 * showed the manifest path alone unreliable -- Play services logs "Failed to deliver message to
 * AppKey[...]" and our code never runs at all, which matches Android's post-Oreo restriction on
 * other processes cold-starting a background service in an app that isn't in the foreground.
 * The live listener sidesteps that because the app registers it itself while already running.
 * [handleDataEvents] is the shared parsing/apply logic both paths call into.
 */
class SettingsSyncListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        handleDataEvents(applicationContext, dataEvents)
    }

    companion object {
        fun handleDataEvents(context: Context, dataEvents: DataEventBuffer) {
            Log.d("SettingsSync", "onDataChanged fired with ${dataEvents.count} event(s)")

            val relevant = dataEvents.firstOrNull {
                it.type == DataEvent.TYPE_CHANGED &&
                        it.dataItem.uri.path == DataLayerPaths.SETTINGS_SYNC
            }
            if (relevant == null) {
                Log.d("SettingsSync", "No matching /settings/sync event in this batch")
                return
            }

            val map = DataMapItem.fromDataItem(relevant.dataItem).dataMap
            Log.d(
                "SettingsSync",
                "Received settings from phone: upper_bpm=${map.getInt("upper_bpm")}"
            )
            val settings = TrainingSettings(
                lowerThresholdBpm = map.getInt("lower_bpm"),
                upperThresholdBpm = map.getInt("upper_bpm"),
                thresholdMode = map.getString("threshold_mode")?.let {
                    runCatching { ThresholdMode.valueOf(it) }.getOrNull()
                } ?: ThresholdMode.BPM,
                lowerThresholdPercent = map.getInt("lower_percent"),
                upperThresholdPercent = map.getInt("upper_percent"),
                breakTimerSeconds = map.getInt("break_seconds"),
                useGpsForDistance = map.getBoolean("use_gps"),
                vibrationEnabled = map.getBoolean("vibration_enabled"),
                distanceTargetMeters = map.getFloat("target_meters").takeIf { it > 0f }
            )

            CoroutineScope(Dispatchers.IO).launch {
                SettingsStore(context).save(settings)
            }
        }
    }
}