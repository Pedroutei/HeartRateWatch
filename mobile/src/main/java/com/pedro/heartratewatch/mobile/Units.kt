package com.pedro.heartratewatch.mobile

import android.content.Context
import com.pedro.heartratewatch.shared.DistanceUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only view over SettingsRepository's paceUnit/distanceUnit fields (see TrainingSettings),
 * for screens that just display runs formatted in the current unit (RunHistoryActivity,
 * MonthlyChartActivity) without editing settings themselves. The Settings screen itself edits
 * these two directly as part of its draft/Save flow instead of through here -- see
 * MainActivity.SettingsScreen -- so a unit change there doesn't silently persist (and push to the
 * watch) before the rest of an in-progress edit is saved. These are synced to the watch, so its
 * tile and screen format pace/distance the same way the phone does.
 */
class UnitPreferencesRepository(context: Context) {

    private val settingsRepository = SettingsRepository(context)

    val paceUnitFlow: Flow<DistanceUnit> = settingsRepository.settingsFlow.map { it.paceUnit }
    val targetDistanceUnitFlow: Flow<DistanceUnit> = settingsRepository.settingsFlow.map { it.distanceUnit }
}
