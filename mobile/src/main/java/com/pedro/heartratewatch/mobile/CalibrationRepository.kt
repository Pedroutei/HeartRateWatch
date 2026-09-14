package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
}
