package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.workoutDataStore by preferencesDataStore(name = "workouts")

/**
 * Everything the Workout side of the app stores: the exercise database, templates, finished
 * workouts, and the one workout currently in progress. Phone-only (nothing syncs to the watch).
 * Each is a small JSON string in a Preferences DataStore -- same approach as RunHistoryRepository,
 * and plenty for notepad-sized data without adding a database dependency.
 */
class WorkoutRepository(private val context: Context) {

    private object Keys {
        val EXERCISES = stringPreferencesKey("exercises")
        val TEMPLATES = stringPreferencesKey("templates")
        val HISTORY = stringPreferencesKey("history")
        val ACTIVE = stringPreferencesKey("active")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
    }

    val exercisesFlow: Flow<List<Exercise>> = context.workoutDataStore.data.map { prefs ->
        prefs[Keys.EXERCISES]?.let(::parseExercises) ?: DEFAULT_EXERCISES
    }

    val templatesFlow: Flow<List<WorkoutTemplate>> = context.workoutDataStore.data.map { prefs ->
        prefs[Keys.TEMPLATES]?.let(::parseTemplates).orEmpty()
    }

    /** Finished workouts, newest first. */
    val historyFlow: Flow<List<WorkoutLog>> = context.workoutDataStore.data.map { prefs ->
        prefs[Keys.HISTORY]?.let(::parseWorkouts).orEmpty().sortedByDescending { it.startedAtMillis }
    }

    val activeFlow: Flow<WorkoutLog?> = context.workoutDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE]?.let { parseWorkout(JSONObject(it)) }
    }

    val weightUnitFlow: Flow<WeightUnit> = context.workoutDataStore.data.map { prefs ->
        prefs[Keys.WEIGHT_UNIT]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.LB
    }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.workoutDataStore.edit { it[Keys.WEIGHT_UNIT] = unit.name }
    }

    suspend fun addExercise(name: String): Exercise {
        val exercise = Exercise(id = newId(), name = name.trim())
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.EXERCISES]?.let(::parseExercises) ?: DEFAULT_EXERCISES
            prefs[Keys.EXERCISES] = exercisesToJson(current + exercise)
        }
        return exercise
    }

    suspend fun deleteExercise(id: String) {
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.EXERCISES]?.let(::parseExercises) ?: DEFAULT_EXERCISES
            prefs[Keys.EXERCISES] = exercisesToJson(current.filterNot { it.id == id })
        }
    }

    /** Puts back any built-in exercise that was deleted, leaving everything else as it is. */
    suspend fun restoreDefaultExercises() {
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.EXERCISES]?.let(::parseExercises) ?: DEFAULT_EXERCISES
            val missing = DEFAULT_EXERCISES.filter { default -> current.none { it.id == default.id } }
            prefs[Keys.EXERCISES] = exercisesToJson(current + missing)
        }
    }

    suspend fun saveTemplate(template: WorkoutTemplate) {
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.TEMPLATES]?.let(::parseTemplates).orEmpty()
            val updated = if (current.any { it.id == template.id }) {
                current.map { if (it.id == template.id) template else it }
            } else {
                current + template
            }
            prefs[Keys.TEMPLATES] = templatesToJson(updated)
        }
    }

    suspend fun deleteTemplate(id: String) {
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.TEMPLATES]?.let(::parseTemplates).orEmpty()
            prefs[Keys.TEMPLATES] = templatesToJson(current.filterNot { it.id == id })
        }
    }

    /** Saves (or, with null, clears) the in-progress workout, so it survives the app closing. */
    suspend fun saveActive(workout: WorkoutLog?) {
        context.workoutDataStore.edit { prefs ->
            if (workout == null) prefs.remove(Keys.ACTIVE) else prefs[Keys.ACTIVE] = workoutToJson(workout).toString()
        }
    }

    /** Moves the in-progress workout into history. */
    suspend fun finish(workout: WorkoutLog) {
        context.workoutDataStore.edit { prefs ->
            val finished = workout.copy(finishedAtMillis = System.currentTimeMillis())
            val current = prefs[Keys.HISTORY]?.let(::parseWorkouts).orEmpty()
            prefs[Keys.HISTORY] = workoutsToJson(current + finished)
            prefs.remove(Keys.ACTIVE)
        }
    }

    suspend fun deleteWorkout(id: String) {
        context.workoutDataStore.edit { prefs ->
            val current = prefs[Keys.HISTORY]?.let(::parseWorkouts).orEmpty()
            prefs[Keys.HISTORY] = workoutsToJson(current.filterNot { it.id == id })
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }

    // ---- JSON ----

    private fun exercisesToJson(list: List<Exercise>) = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("builtIn", it.builtIn)) }
    }.toString()

    private fun parseExercises(json: String): List<Exercise> = JSONArray(json).let { arr ->
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Exercise(o.getString("id"), o.getString("name"), o.optBoolean("builtIn", false))
        }
    }

    private fun templatesToJson(list: List<WorkoutTemplate>) = JSONArray().also { arr ->
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("exerciseIds", JSONArray(it.exerciseIds))
            )
        }
    }.toString()

    private fun parseTemplates(json: String): List<WorkoutTemplate> = JSONArray(json).let { arr ->
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val ids = o.getJSONArray("exerciseIds")
            WorkoutTemplate(o.getString("id"), o.getString("name"), (0 until ids.length()).map(ids::getString))
        }
    }

    private fun workoutsToJson(list: List<WorkoutLog>) = JSONArray().also { arr ->
        list.forEach { arr.put(workoutToJson(it)) }
    }.toString()

    private fun parseWorkouts(json: String): List<WorkoutLog> = JSONArray(json).let { arr ->
        (0 until arr.length()).map { parseWorkout(arr.getJSONObject(it)) }
    }

    private fun workoutToJson(w: WorkoutLog): JSONObject = JSONObject()
        .put("id", w.id)
        .put("startedAt", w.startedAtMillis)
        .put("finishedAt", w.finishedAtMillis ?: JSONObject.NULL)
        .put("templateName", w.templateName)
        .put("exercises", JSONArray().also { exercises ->
            w.exercises.forEach { e ->
                exercises.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("exerciseId", e.exerciseId)
                        .put("exerciseName", e.exerciseName)
                        .put("intensity", e.intensity?.name ?: JSONObject.NULL)
                        .put("note", e.note)
                        .put("sets", JSONArray().also { sets ->
                            e.sets.forEach { s ->
                                sets.put(
                                    JSONObject()
                                        .put("id", s.id)
                                        .put("weight", s.weight ?: JSONObject.NULL)
                                        .put("reps", s.reps ?: JSONObject.NULL)
                                )
                            }
                        })
                )
            }
        })

    private fun parseWorkout(o: JSONObject): WorkoutLog {
        val exercises = o.getJSONArray("exercises")
        return WorkoutLog(
            id = o.getString("id"),
            startedAtMillis = o.getLong("startedAt"),
            finishedAtMillis = if (o.isNull("finishedAt")) null else o.getLong("finishedAt"),
            templateName = o.getString("templateName"),
            exercises = (0 until exercises.length()).map { i ->
                val e = exercises.getJSONObject(i)
                val sets = e.getJSONArray("sets")
                ExerciseLog(
                    id = e.getString("id"),
                    exerciseId = e.getString("exerciseId"),
                    exerciseName = e.getString("exerciseName"),
                    intensity = if (e.isNull("intensity")) null else {
                        runCatching { Intensity.valueOf(e.getString("intensity")) }.getOrNull()
                    },
                    note = e.optString("note", ""),
                    sets = (0 until sets.length()).map { j ->
                        val s = sets.getJSONObject(j)
                        LoggedSet(
                            id = s.getString("id"),
                            weight = if (s.isNull("weight")) null else s.getDouble("weight"),
                            reps = if (s.isNull("reps")) null else s.getInt("reps")
                        )
                    }
                )
            }
        )
    }
}
