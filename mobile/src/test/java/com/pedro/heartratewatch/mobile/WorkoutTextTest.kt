package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutTextTest {

    private fun log(name: String, sets: List<LoggedSet>, note: String = "", intensity: Intensity? = null) =
        ExerciseLog("id-$name", "ex-$name", name, sets, intensity, note)

    @Test
    fun collapsesEqualSetsAndKeepsOrder() {
        val workout = WorkoutLog(
            id = "w", startedAtMillis = 0, templateName = "Leg day", note = "short on time",
            exercises = listOf(
                log("Squat", listOf(LoggedSet("1", 135.0, 5), LoggedSet("2", 135.0, 5), LoggedSet("3", 155.0, 3)), note = "felt good"),
                log("Dip", listOf(LoggedSet("4", -30.0, 8), LoggedSet("5", -30.0, 8), LoggedSet("6", -30.0, 8))),
                log("Situps", listOf(LoggedSet("7", null, 20), LoggedSet("8", null, 20)), intensity = Intensity.HARD),
                log("Bench Press", listOf(LoggedSet("9", 132.5, 5)), note = "pause reps, hard", intensity = Intensity.HARD)
            )
        )
        assertEquals(
            listOf(
                "12",
                "Squat 2*5 135lbs 1*3 155lbs - felt good",
                "Dip 3*8 -30lbs",
                "Situps 2*20 - hard",
                "Bench Press 1*5 132.5lbs - pause reps, hard",
                "Workout note: short on time"
            ).joinToString("\n"),
            WorkoutText.format(workout, 12, WeightUnit.LB)
        )
    }

    @Test
    fun usesTheChosenUnit() {
        val line = WorkoutText.exerciseLine(log("Squat", listOf(LoggedSet("1", 220.46226218, 5))), WeightUnit.KG)
        assertEquals("Squat 1*5 100kg", line)
    }
}
