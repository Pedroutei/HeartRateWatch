package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dashboardStatsDataStore by preferencesDataStore(name = "dashboard_stats")

data class DashboardStats(
    val streakDays: Int,
    val thisMonthMeters: Double,
    val lastWorkoutMillis: Long?
)

/**
 * Local, on-watch cache of the same at-a-glance stats the phone's own home screen shows (see
 * DashboardStatsRow in mobile's MainActivity.kt) -- the watch has no run history of its own, only
 * whatever the phone last pushed here (see SettingsSyncListenerService, which also handles the
 * "/dashboard/stats" DataItem despite its name). Read by the tile while idle.
 */
class DashboardStatsStore(private val context: Context) {

    private object Keys {
        val STREAK_DAYS = intPreferencesKey("streak_days")
        val THIS_MONTH_METERS = doublePreferencesKey("this_month_meters")
        val LAST_WORKOUT_MILLIS = longPreferencesKey("last_workout_millis")
    }

    val statsFlow: Flow<DashboardStats> = context.dashboardStatsDataStore.data.map { prefs ->
        DashboardStats(
            streakDays = prefs[Keys.STREAK_DAYS] ?: 0,
            thisMonthMeters = prefs[Keys.THIS_MONTH_METERS] ?: 0.0,
            lastWorkoutMillis = prefs[Keys.LAST_WORKOUT_MILLIS]?.takeIf { it > 0L }
        )
    }

    suspend fun save(stats: DashboardStats) {
        context.dashboardStatsDataStore.edit { prefs ->
            prefs[Keys.STREAK_DAYS] = stats.streakDays
            prefs[Keys.THIS_MONTH_METERS] = stats.thisMonthMeters
            prefs[Keys.LAST_WORKOUT_MILLIS] = stats.lastWorkoutMillis ?: -1L
        }
    }
}
