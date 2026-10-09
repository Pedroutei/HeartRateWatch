package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar

class WorkoutExporterTest {

    private fun sampleWorkouts(): List<WorkoutLog> {
        val day1 = Calendar.getInstance().apply { clear(); set(2025, Calendar.MARCH, 14, 17, 30) }.timeInMillis
        val day2 = Calendar.getInstance().apply { clear(); set(2025, Calendar.MARCH, 17, 9, 5) }.timeInMillis
        return listOf(
            // deliberately out of order, and with an imported id and a native one
            WorkoutLog(
                id = "native-uuid", startedAtMillis = day2, templateName = "Push day", note = "felt strong & fast <3",
                exercises = listOf(
                    ExerciseLog("e1", "x", "Bench Press", listOf(LoggedSet("s1", 135.0, 8), LoggedSet("s2", 137.5, 6)), Intensity.OK, "paused reps")
                )
            ),
            WorkoutLog(
                id = "imported-4", startedAtMillis = day1, templateName = "Workout 4", note = "",
                exercises = listOf(
                    ExerciseLog("e2", "y", "Dip", listOf(LoggedSet("s3", -30.0, 8)), Intensity.HARD, ""),
                    ExerciseLog("e3", "z", "Situps", listOf(LoggedSet("s4", null, 20)), null, "")
                )
            )
        )
    }

    @Test
    fun exportThenImportRoundTrips() {
        val bytes = ByteArrayOutputStream().also { WorkoutExporter.write(sampleWorkouts(), it) }.toByteArray()
        val plan = WorkoutImporter.plan(ByteArrayInputStream(bytes), existing = emptyList())

        assertEquals(2, plan.workouts.size)
        assertEquals(4, plan.setCount)
        // same ids come back, so importing an export onto the same phone replaces instead of duplicating
        assertEquals(setOf("imported-4", "native-uuid"), plan.workouts.map { it.id }.toSet())

        val native = plan.workouts.first { it.id == "native-uuid" }
        assertEquals("felt strong & fast <3", native.note)
        assertEquals(listOf(135.0, 137.5), native.exercises[0].sets.map { it.weight })
        assertEquals(listOf(8, 6), native.exercises[0].sets.map { it.reps })
        assertEquals("paused reps", native.exercises[0].note)
        assertEquals(Intensity.OK, native.exercises[0].intensity)

        val imported = plan.workouts.first { it.id == "imported-4" }
        assertEquals(-30.0, imported.exercises[0].sets[0].weight!!, 0.0001)
        assertEquals(Intensity.HARD, imported.exercises[0].intensity)
        assertEquals(null, imported.exercises[1].sets[0].weight)
        assertEquals(20, imported.exercises[1].sets[0].reps)

        // date AND time survive
        val cal = Calendar.getInstance().apply { timeInMillis = native.startedAtMillis }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, cal.get(Calendar.MINUTE))
    }

    @Test
    fun numbersContinueAfterImportedOnesInDateOrder() {
        val bytes = ByteArrayOutputStream().also { WorkoutExporter.write(sampleWorkouts(), it) }.toByteArray()
        val rows = WorkoutImporter.readSheet(ByteArrayInputStream(bytes))
        assertEquals(WorkoutExporter.HEADERS, rows[0])
        // the imported workout keeps its own number (4); the native one is numbered after it
        assertEquals(listOf("4", "5"), rows.drop(1).map { it[0] }.distinct())
    }

    @Test
    fun emptyHistoryStillProducesAReadableSheet() {
        val bytes = ByteArrayOutputStream().also { WorkoutExporter.write(emptyList(), it) }.toByteArray()
        val plan = WorkoutImporter.plan(ByteArrayInputStream(bytes), emptyList())
        assertTrue(plan.workouts.isEmpty())
    }
}
