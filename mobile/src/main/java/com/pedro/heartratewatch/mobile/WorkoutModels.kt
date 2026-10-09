package com.pedro.heartratewatch.mobile

/** How hard an exercise felt, picked per exercise with the red/yellow/green buttons. */
enum class Intensity(val label: String) {
    HARD("Hard"),
    OK("OK"),
    EASY("Easy")
}

/**
 * Weights are always STORED in pounds and converted only for display/entry, so switching units
 * never rewrites history: a workout logged in lb reads correctly in kg and vice versa.
 */
enum class WeightUnit(val symbol: String, private val lbPerUnit: Double) {
    LB("lb", 1.0),
    KG("kg", 2.2046226218);

    fun fromLb(lb: Double): Double = lb / lbPerUnit
    fun toLb(value: Double): Double = value * lbPerUnit
}

/** One entry in the exercise database. Built-ins ship with the app but can be deleted like any other (and restored). */
data class Exercise(val id: String, val name: String, val builtIn: Boolean = false)

/** A reusable workout (e.g. "Leg day"): just an ordered list of exercises to start from. */
data class WorkoutTemplate(val id: String, val name: String, val exerciseIds: List<String>)

/** One set. Either field can be blank while it's still being filled in. */
data class LoggedSet(val id: String, val weight: Double? = null, val reps: Int? = null)

/**
 * One exercise within a workout. [exerciseName] is copied in rather than looked up, so history
 * still reads correctly after an exercise is deleted from the database.
 */
data class ExerciseLog(
    val id: String,
    val exerciseId: String,
    val exerciseName: String,
    val sets: List<LoggedSet> = emptyList(),
    val intensity: Intensity? = null,
    val note: String = ""
)

/** A workout in progress (finishedAtMillis == null) or in history. */
data class WorkoutLog(
    val id: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val templateName: String,
    val exercises: List<ExerciseLog>
)

/** Starting set of exercises, so the database isn't empty on first launch. */
val DEFAULT_EXERCISES: List<Exercise> = listOf(
    "Squat", "Deadlift", "Bench Press", "Overhead Press", "Barbell Row", "Pull-Up", "Chin-Up",
    "Dip", "Lunge", "Leg Press", "Romanian Deadlift", "Lat Pulldown", "Incline Bench Press",
    "Dumbbell Curl", "Triceps Pushdown", "Lateral Raise", "Leg Curl", "Leg Extension",
    "Calf Raise", "Plank"
).map { Exercise(id = "builtin_" + it.lowercase().replace(Regex("[^a-z0-9]+"), "_"), name = it, builtIn = true) }
