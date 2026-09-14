package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.runHistoryDataStore by preferencesDataStore(name = "run_history")

/**
 * Local history of completed runs, populated from the "/run/summary" message
 * ExerciseSessionService sends from the watch each time a run stops (see
 * WearMessageListenerService). Stored as a delimited string, same approach as CalibrationStore on
 * :wear, to avoid pulling in a JSON dependency for one small list.
 */
class RunHistoryRepository(private val context: Context) {

    private object Keys {
        val HISTORY = stringPreferencesKey("history")
    }

    /** Most recent run first. */
    val historyFlow: Flow<List<RunSummary>> = context.runHistoryDataStore.data.map { prefs ->
        parse(prefs[Keys.HISTORY]).sortedByDescending { it.startedAtMillis }
    }

    suspend fun addRun(summary: RunSummary) {
        context.runHistoryDataStore.edit { prefs ->
            val updated = (parse(prefs[Keys.HISTORY]) + summary).takeLast(MAX_HISTORY)
            prefs[Keys.HISTORY] = serialize(updated)
        }
    }

    private fun parse(raw: String?): List<RunSummary> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 6) return@mapNotNull null
            runCatching {
                RunSummary(
                    startedAtMillis = parts[0].toLong(),
                    durationSeconds = parts[1].toInt(),
                    avgBpm = parts[2].toInt(),
                    maxBpm = parts[3].toInt(),
                    minBpm = parts[4].toInt(),
                    distanceMeters = parts[5].toFloat()
                )
            }.getOrNull()
        }
    }

    private fun serialize(runs: List<RunSummary>): String = runs.joinToString(";") {
        "${it.startedAtMillis},${it.durationSeconds},${it.avgBpm},${it.maxBpm},${it.minBpm},${it.distanceMeters}"
    }

    companion object {
        private const val MAX_HISTORY = 30
    }
}
