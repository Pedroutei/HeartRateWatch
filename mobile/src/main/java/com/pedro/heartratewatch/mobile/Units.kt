package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Units offered for the distance target field. */
enum class DistanceUnit(val symbol: String, val metersPerUnit: Double) {
    KILOMETERS("km", 1000.0),
    MILES("mi", 1609.344),
    METERS("meters", 1.0),
    FEET("feet", 0.3048),
    YARDS("yards", 0.9144),
    FOOTBALL_FIELDS("football fields", 91.44),
    BANANAS("bananas", 0.18)
}

/**
 * Units offered for pace, deliberately narrower than [DistanceUnit] as a whole -- pace as
 * "time per banana" doesn't produce a meaningful number (a marathon pace is a tiny fraction of a
 * second per banana), so only the two units runners actually pace themselves by are offered here.
 */
val PACE_UNITS = listOf(DistanceUnit.KILOMETERS, DistanceUnit.MILES)

private val Context.unitPreferencesDataStore by preferencesDataStore(name = "unit_preferences")

/**
 * Phone-local display-unit preferences for the distance target and pace fields -- purely a data
 * entry/presentation convenience, deliberately NOT part of TrainingSettings and NOT synced to the
 * watch. The underlying values stay in fixed canonical units everywhere else in the app (meters
 * for distance, seconds per km for pace) -- including in ExerciseSessionService's threshold
 * checks and in RunSummary -- so this preference can never cause what you see here to drift from
 * what actually triggers an alert.
 */
class UnitPreferencesRepository(private val context: Context) {

    private object Keys {
        val TARGET_DISTANCE_UNIT = stringPreferencesKey("target_distance_unit")
        val PACE_UNIT = stringPreferencesKey("pace_unit")
    }

    val targetDistanceUnitFlow: Flow<DistanceUnit> = context.unitPreferencesDataStore.data.map { prefs ->
        prefs[Keys.TARGET_DISTANCE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.KILOMETERS
    }

    val paceUnitFlow: Flow<DistanceUnit> = context.unitPreferencesDataStore.data.map { prefs ->
        prefs[Keys.PACE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.KILOMETERS
    }

    suspend fun setTargetDistanceUnit(unit: DistanceUnit) {
        context.unitPreferencesDataStore.edit { it[Keys.TARGET_DISTANCE_UNIT] = unit.name }
    }

    suspend fun setPaceUnit(unit: DistanceUnit) {
        context.unitPreferencesDataStore.edit { it[Keys.PACE_UNIT] = unit.name }
    }
}
