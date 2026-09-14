package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.DataLayerPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

private val Context.calibrationDataStore by preferencesDataStore(name = "max_hr_calibration")

data class CalibrationResult(val bpm: Int, val recordedAtMillis: Long)

/**
 * Keeps a short history of completed guided max-HR calibration tests (see CalibrationActivity),
 * so you can see whether your number has drifted over time rather than only ever knowing the
 * latest one. Stored as a delimited string rather than pulling in a JSON dependency for this.
 *
 * Also pushes the latest result to the phone (as a Message, mirroring the "/alert/..." pattern
 * ExerciseSessionService already uses) so it's visible there too while setting bpm thresholds.
 */
class CalibrationStore(private val context: Context) {

    private object Keys {
        val HISTORY = stringPreferencesKey("history")
    }

    val historyFlow: Flow<List<CalibrationResult>> = context.calibrationDataStore.data.map { prefs ->
        parse(prefs[Keys.HISTORY])
    }

    val latestFlow: Flow<CalibrationResult?> = historyFlow.map { it.lastOrNull() }

    suspend fun recordResult(bpm: Int, recordedAtMillis: Long = System.currentTimeMillis()) {
        context.calibrationDataStore.edit { prefs ->
            val updated = (parse(prefs[Keys.HISTORY]) + CalibrationResult(bpm, recordedAtMillis))
                .takeLast(MAX_HISTORY)
            prefs[Keys.HISTORY] = serialize(updated)
        }
        sendToPhone(bpm, recordedAtMillis)
    }

    /**
     * Independent scope (not the caller's) since CalibrationActivity calls recordResult() right
     * before it finishes -- if this awaited on the caller's own scope, finishing would cancel
     * the connected-nodes lookup before the message actually sent, and the phone would never see
     * the result even though the local save above already succeeded.
     */
    private fun sendToPhone(bpm: Int, recordedAtMillis: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val nodes = runCatching {
                Wearable.getNodeClient(context).connectedNodes.await()
            }.getOrNull().orEmpty()

            val payload = ByteBuffer.allocate(12).putInt(bpm).putLong(recordedAtMillis).array()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, DataLayerPaths.CALIBRATION_RESULT, payload)
            }
        }
    }

    private fun parse(raw: String?): List<CalibrationResult> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val millis = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bpm = parts[1].toIntOrNull() ?: return@mapNotNull null
            CalibrationResult(bpm, millis)
        }
    }

    private fun serialize(results: List<CalibrationResult>): String =
        results.joinToString(";") { "${it.recordedAtMillis}:${it.bpm}" }

    companion object {
        private const val MAX_HISTORY = 10
    }
}
