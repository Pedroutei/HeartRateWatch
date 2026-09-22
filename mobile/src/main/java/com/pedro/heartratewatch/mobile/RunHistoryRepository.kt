package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.runHistoryDataStore by preferencesDataStore(name = "run_history")

/** Header row for exported CSV files -- also documents the column order used internally. */
private const val CSV_HEADER = "started_at_millis,duration_seconds,avg_bpm,max_bpm,min_bpm," +
    "distance_meters,avg_pace_sec_per_km,best_1km_seconds,best_5km_seconds,best_10km_seconds"

/**
 * Local history of completed runs, populated from the "/run/summary" message
 * ExerciseSessionService sends from the watch each time a run stops (see
 * WearMessageListenerService). Stored as a delimited string, same approach as CalibrationStore on
 * :wear, to avoid pulling in a JSON dependency for this. No cap on how many runs are kept -- each
 * record is under 60 bytes, so even a few thousand runs is a trivial amount of text for
 * Preferences DataStore, and capping it would silently break both the leaderboard and CSV export.
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
            val updated = parse(prefs[Keys.HISTORY]) + summary
            prefs[Keys.HISTORY] = serialize(updated)
        }
    }

    /** Identifies a run by its start time, which is unique per run. */
    suspend fun removeRun(startedAtMillis: Long) {
        context.runHistoryDataStore.edit { prefs ->
            val updated = parse(prefs[Keys.HISTORY]).filterNot { it.startedAtMillis == startedAtMillis }
            prefs[Keys.HISTORY] = serialize(updated)
        }
    }

    /**
     * Merges in runs from an imported file, skipping any whose start time already exists so a
     * re-import (or importing an export that overlaps current history) doesn't create duplicates.
     * Returns how many were actually added.
     */
    suspend fun importRuns(runs: List<RunSummary>): Int {
        var added = 0
        context.runHistoryDataStore.edit { prefs ->
            val existing = parse(prefs[Keys.HISTORY])
            val existingStarts = existing.mapTo(HashSet()) { it.startedAtMillis }
            val toAdd = runs.filterNot { it.startedAtMillis in existingStarts }
            added = toAdd.size
            prefs[Keys.HISTORY] = serialize(existing + toAdd)
        }
        return added
    }

    suspend fun exportCsv(): String {
        val runs = historyFlow.first().sortedBy { it.startedAtMillis }
        return CSV_HEADER + "\n" + serialize(runs)
    }

    /** Parses a file previously produced by [exportCsv] (the header line is optional/ignored). */
    fun parseCsv(csv: String): List<RunSummary> {
        val body = csv.lineSequence().filterNot { it == CSV_HEADER }.joinToString("\n")
        return parse(body)
    }

    private fun parse(raw: String?): List<RunSummary> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(",")
            if (parts.size != 10) return@mapNotNull null
            runCatching {
                RunSummary(
                    startedAtMillis = parts[0].toLong(),
                    durationSeconds = parts[1].toInt(),
                    avgBpm = parts[2].toInt(),
                    maxBpm = parts[3].toInt(),
                    minBpm = parts[4].toInt(),
                    distanceMeters = parts[5].toFloat(),
                    avgPaceSecPerKm = parts[6].toInt().takeIf { it > 0 },
                    best1kmSeconds = parts[7].toInt().takeIf { it > 0 },
                    best5kmSeconds = parts[8].toInt().takeIf { it > 0 },
                    best10kmSeconds = parts[9].toInt().takeIf { it > 0 }
                )
            }.getOrNull()
        }.toList()
    }

    private fun serialize(runs: List<RunSummary>): String = runs.joinToString("\n") {
        "${it.startedAtMillis},${it.durationSeconds},${it.avgBpm},${it.maxBpm},${it.minBpm}," +
            "${it.distanceMeters},${it.avgPaceSecPerKm ?: -1}," +
            "${it.best1kmSeconds ?: -1},${it.best5kmSeconds ?: -1},${it.best10kmSeconds ?: -1}"
    }
}
