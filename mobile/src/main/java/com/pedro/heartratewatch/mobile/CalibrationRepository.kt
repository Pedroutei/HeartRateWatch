package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.DataLayerPaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

private val Context.calibrationDataStore by preferencesDataStore(name = "max_hr_calibration")

data class CalibrationResult(val bpm: Int, val recordedAtMillis: Long)

/**
 * Mirrors the latest guided max-HR calibration result pushed from the watch (see CalibrationStore
 * on :wear) so it's visible here on the phone's bigger screen while setting bpm thresholds. The
 * watch keeps the full history; the phone only needs the most recent value.
 */
class CalibrationRepository(private val context: Context) {

    private object Keys {
        val BPM = intPreferencesKey("bpm")
        val RECORDED_AT = longPreferencesKey("recorded_at")
    }

    val latestFlow: Flow<CalibrationResult?> = context.calibrationDataStore.data.map { prefs ->
        val bpm = prefs[Keys.BPM] ?: return@map null
        val recordedAt = prefs[Keys.RECORDED_AT] ?: return@map null
        CalibrationResult(bpm, recordedAt)
    }

    suspend fun save(result: CalibrationResult) {
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.BPM] = result.bpm
            prefs[Keys.RECORDED_AT] = result.recordedAtMillis
        }
    }

    /**
     * Tells the watch to open its guided calibration screen, so you don't have to go dig for the
     * button on the watch's small screen yourself. The actual test still has to run on the watch
     * (that's where the HR sensor is) -- see PhoneMessageListenerService on :wear for the
     * receiving end, including why there's a notification fallback there.
     */
    suspend fun requestCalibrationOnWatch() {
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrNull().orEmpty()

        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, DataLayerPaths.START_CALIBRATION, ByteArray(0))
        }
    }
}
