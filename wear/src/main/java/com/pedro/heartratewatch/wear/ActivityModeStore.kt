package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.ActivityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.activityModeDataStore by preferencesDataStore(name = "activity_mode")

/**
 * The exercise type "Start" will begin next time, chosen via ExercisePickerActivity. Read by
 * MainActivity's Start button and by the Tile (both the readout layout and what its own start
 * button launches), so they all agree on "what am I about to start" without the Tile needing to
 * duplicate ExercisePickerActivity's UI.
 */
class ActivityModeStore(private val context: Context) {

    private object Keys {
        val SELECTED = stringPreferencesKey("selected_activity_type")
    }

    val selectedFlow: Flow<ActivityType> = context.activityModeDataStore.data.map { prefs ->
        prefs[Keys.SELECTED]?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() } ?: ActivityType.RUN
    }

    suspend fun current(): ActivityType = selectedFlow.first()

    suspend fun select(type: ActivityType) {
        context.activityModeDataStore.edit { it[Keys.SELECTED] = type.name }
    }
}

/** Short label for pickers/tiles/buttons -- kept out of :shared since it's UI text, not a model concern. */
fun ActivityType.displayName(): String = when (this) {
    ActivityType.RUN -> "Running"
    ActivityType.STATIONARY_BIKE -> "Stationary bike"
}
