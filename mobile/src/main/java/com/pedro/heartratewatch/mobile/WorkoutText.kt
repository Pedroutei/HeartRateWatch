package com.pedro.heartratewatch.mobile

import java.util.Locale

/**
 * One workout as plain text, in the same notepad style as the original log: the workout's number,
 * then a line per exercise like `Squat 3*5 135lbs - felt good`. Equal consecutive sets are
 * collapsed (`3*5 135lbs`), assisted weights are negative (`-30lbs`), and anything after ` - ` is
 * the note.
 */
object WorkoutText {

    fun format(workout: WorkoutLog, number: Int, unit: WeightUnit): String {
        val lines = mutableListOf(number.toString())
        for (log in workout.exercises) {
            lines += exerciseLine(log, unit)
        }
        if (workout.note.isNotBlank()) lines += "Workout note: ${workout.note.trim()}"
        return lines.joinToString("\n")
    }

    internal fun exerciseLine(log: ExerciseLog, unit: WeightUnit): String {
        val groups = mutableListOf<Triple<Int, Double?, Int?>>()   // count, weight (lb), reps
        for (set in log.sets) {
            val last = groups.lastOrNull()
            if (last != null && last.second == set.weight && last.third == set.reps) {
                groups[groups.lastIndex] = last.copy(first = last.first + 1)
            } else {
                groups += Triple(1, set.weight, set.reps)
            }
        }
        val sets = groups.joinToString(" ") { (count, weight, reps) ->
            val shape = "$count*${reps ?: "?"}"
            if (weight == null) shape else "$shape ${weightText(weight, unit)}"
        }
        val note = commentOf(log)
        return buildString {
            append(log.exerciseName)
            if (sets.isNotEmpty()) append(' ').append(sets)
            if (note.isNotEmpty()) append(" - ").append(note)
        }
    }

    private fun weightText(weightLb: Double, unit: WeightUnit): String =
        String.format(Locale.US, "%.2f", unit.fromLb(weightLb)).trimEnd('0').trimEnd('.') +
            (if (unit == WeightUnit.LB) "lbs" else unit.symbol)   // "lbs", as written in the original log

    /** The note, plus the intensity as a word if the note doesn't already say it. */
    private fun commentOf(log: ExerciseLog): String {
        val note = log.note.trim()
        val word = log.intensity?.label?.lowercase() ?: return note
        return when {
            note.isEmpty() -> word
            note.contains(word, ignoreCase = true) -> note
            else -> "$note, $word"
        }
    }
}
