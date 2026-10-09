package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkoutImporterTest {

    /** Builds a minimal .xlsx: strings go through sharedStrings.xml like Excel writes them. */
    private fun xlsx(rows: List<List<Any?>>): ByteArrayInputStream {
        val shared = mutableListOf<String>()
        fun colLetter(i: Int) = ('A' + i).toString()
        val sheet = StringBuilder("""<?xml version="1.0"?><worksheet><sheetData>""")
        rows.forEachIndexed { r, row ->
            sheet.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                val ref = "${colLetter(c)}${r + 1}"
                when (value) {
                    null -> {}
                    is Number -> sheet.append("""<c r="$ref"><v>$value</v></c>""")
                    else -> {
                        shared += value.toString()
                        sheet.append("""<c r="$ref" t="s"><v>${shared.size - 1}</v></c>""")
                    }
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val sst = shared.joinToString("", """<?xml version="1.0"?><sst>""", "</sst>") {
            "<si><t>${it.replace("&", "&amp;").replace("<", "&lt;")}</t></si>"
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml")); zip.write(sst.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheet.toString().toByteArray()); zip.closeEntry()
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val header = listOf("Workout", "Date", "Exercise", "Set", "Weight (lb)", "Reps", "Exercise note", "Intensity", "Workout note")

    @Test
    fun groupsSetsIntoExercisesAndWorkouts() {
        val plan = WorkoutImporter.plan(
            xlsx(
                listOf(
                    header,
                    listOf(1, "2025-03-14", "Squat", 1, 135, 5, "felt good", "Easy", "leg day"),
                    listOf(1, "2025-03-14", "Squat", 2, 135, 5, null, null, null),
                    listOf(1, "2025-03-14", "Dip", 1, -30, 8, null, null, null),
                    listOf(2, "2025-03-17", "Squat", 1, 145, 3, null, null, null),
                    listOf(2, "2025-03-17", "Dip", 1, null, 10, null, null, null)
                )
            ),
            existing = emptyList()
        )
        assertEquals(2, plan.workouts.size)
        assertEquals(5, plan.setCount)
        val first = plan.workouts[0]
        assertEquals("imported-1", first.id)
        assertEquals("leg day", first.note)
        assertEquals(2, first.exercises.size)
        assertEquals(2, first.exercises[0].sets.size)
        assertEquals("felt good", first.exercises[0].note)
        assertEquals(Intensity.EASY, first.exercises[0].intensity)
        assertEquals(-30.0, first.exercises[1].sets[0].weight!!, 0.0001)
        assertNull(plan.workouts[1].exercises[1].sets[0].weight)
        assertEquals(10, plan.workouts[1].exercises[1].sets[0].reps)
        val cal = Calendar.getInstance().apply { timeInMillis = first.startedAtMillis }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun matchesExistingExercisesIgnoringCase() {
        val existing = listOf(Exercise("sq", "Squat", builtIn = true))
        val plan = WorkoutImporter.plan(
            xlsx(listOf(header, listOf(1, "2025-01-01", "squat", 1, 100, 5), listOf(1, "2025-01-01", "Bench Press", 1, 80, 5))),
            existing
        )
        assertEquals("sq", plan.workouts[0].exercises[0].exerciseId)
        assertEquals(listOf("Bench Press"), plan.newExercises.map { it.name })
    }

    @Test
    fun sameExerciseTwiceInARowIsTwoEntriesWhenTheSetNumberRestarts() {
        val plan = WorkoutImporter.plan(
            xlsx(listOf(header, listOf(1, "2025-01-01", "Squat", 1, 100, 5), listOf(1, "2025-01-01", "Squat", 2, 100, 5), listOf(1, "2025-01-01", "Squat", 1, 120, 3))),
            emptyList()
        )
        assertEquals(2, plan.workouts[0].exercises.size)
    }

    @Test
    fun convertsKilogramsAndExcelDates() {
        val plan = WorkoutImporter.plan(
            xlsx(listOf(listOf("Workout", "Date", "Exercise", "Set", "Weight (kg)", "Reps"), listOf(1, 45000, "Squat", 1, 100, 5))),
            emptyList()
        )
        assertEquals(220.462, plan.workouts[0].exercises[0].sets[0].weight!!, 0.01)
        val cal = Calendar.getInstance().apply { timeInMillis = plan.workouts[0].startedAtMillis }
        assertEquals(2023, cal.get(Calendar.YEAR))        // Excel serial 45000 = 2023-03-15
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun reportsAProblemInsteadOfCrashingOnTheWrongSheet() {
        val plan = WorkoutImporter.plan(xlsx(listOf(listOf("Name", "Age"), listOf("a", 1))), emptyList())
        assertTrue(plan.workouts.isEmpty())
        assertTrue(plan.warnings.isNotEmpty())
    }
}
