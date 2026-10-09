package com.pedro.heartratewatch.mobile

import java.io.InputStream
import java.util.Calendar
import java.util.zip.ZipInputStream

/**
 * Reads a workout log from an Excel (.xlsx) sheet with one row per set. Columns are found by their
 * header names, so they can be in any order (and extra columns are ignored):
 *
 *   Workout | Date | Exercise | Set | Weight (lb) | Reps | Exercise note | Intensity | Workout note
 *
 * "Workout" is the workout's number (rows with the same number are one workout). "Date" can be
 * text like 2025-03-14 or a real Excel date. A "Weight (kg)" header converts to lb. Weights are
 * negative for assisted exercises. Pure Kotlin (no Android classes) so it can be unit tested.
 */
object WorkoutImporter {

    private const val LB_PER_KG = 2.2046226218

    data class Plan(
        val workouts: List<WorkoutLog>,
        val newExercises: List<Exercise>,
        val warnings: List<String>
    ) {
        val setCount: Int get() = workouts.sumOf { w -> w.exercises.sumOf { it.sets.size } }
    }

    /** Parses the sheet into a [Plan]; [existing] are the exercises already in the database. */
    fun plan(xlsx: InputStream, existing: List<Exercise>, nowMillis: Long = System.currentTimeMillis()): Plan =
        build(readSheet(xlsx), existing, nowMillis)

    // ---------------------------------------------------------------- building the plan

    private class Columns(
        val workout: Int, val date: Int, val exercise: Int, val set: Int, val weight: Int,
        val reps: Int, val note: Int, val intensity: Int, val workoutNote: Int, val weightIsKg: Boolean
    )

    private fun columns(header: List<String>): Columns? {
        val h = header.map { it.trim().lowercase() }
        fun find(match: (String) -> Boolean): Int = h.indexOfFirst(match)
        val workout = find { it == "workout" || it == "workout #" || it == "workout number" }
        val exercise = find { it == "exercise" }
        if (workout < 0 || exercise < 0) return null
        val weight = find { it.startsWith("weight") }
        return Columns(
            workout = workout,
            date = find { it == "date" },
            exercise = exercise,
            set = find { it == "set" },
            weight = weight,
            reps = find { it == "reps" },
            note = find { it == "exercise note" || it == "note" || it == "comment" },
            intensity = find { it == "intensity" },
            workoutNote = find { it == "workout note" },
            weightIsKg = weight >= 0 && h[weight].contains("kg")
        )
    }

    private fun build(table: List<List<String>>, existing: List<Exercise>, nowMillis: Long): Plan {
        val warnings = mutableListOf<String>()
        if (table.isEmpty()) return Plan(emptyList(), emptyList(), listOf("The sheet is empty."))
        val cols = columns(table[0])
            ?: return Plan(emptyList(), emptyList(), listOf("Couldn't find the \"Workout\" and \"Exercise\" columns in the first row."))

        fun cell(row: List<String>, index: Int) = if (index in row.indices) row[index].trim() else ""

        val byName = existing.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val created = mutableListOf<Exercise>()
        fun exerciseFor(name: String): Exercise = byName.getOrPut(name.trim().lowercase()) {
            Exercise(id = WorkoutRepository.newId(), name = name.trim()).also { created += it }
        }

        // number -> rows, keeping the sheet's own order within a workout
        val grouped = LinkedHashMap<Int, MutableList<List<String>>>()
        for ((i, row) in table.drop(1).withIndex()) {
            if (row.all { it.isBlank() }) continue
            val number = cell(row, cols.workout).toDoubleOrNull()?.toInt()
            if (number == null) {
                warnings += "Row ${i + 2}: no workout number, skipped."
                continue
            }
            grouped.getOrPut(number) { mutableListOf() } += row
        }

        val ordered = grouped.keys.sorted()
        val workouts = ordered.mapIndexed { position, number ->
            val rows = grouped.getValue(number)
            val startedAt = rows.firstNotNullOfOrNull { parseDate(cell(it, cols.date)) }
                // no date given: stack the workouts one per day, newest last, ending today
                ?: dayAt(nowMillis, -(ordered.size - 1 - position))
            val exerciseLogs = mutableListOf<ExerciseLog>()
            var lastName: String? = null
            var lastSet = 0
            for (row in rows) {
                val name = cell(row, cols.exercise)
                if (name.isBlank()) continue
                val setNumber = cell(row, cols.set).toDoubleOrNull()?.toInt() ?: (lastSet + 1)
                val weightCell = cell(row, cols.weight).toDoubleOrNull()
                val weightLb = weightCell?.let { if (cols.weightIsKg) it * LB_PER_KG else it }
                val reps = cell(row, cols.reps).toDoubleOrNull()?.toInt()
                val sameExercise = name.equals(lastName, ignoreCase = true) && setNumber > lastSet && exerciseLogs.isNotEmpty()
                if (!sameExercise) {
                    val exercise = exerciseFor(name)
                    exerciseLogs += ExerciseLog(
                        id = "imported-$number-${exerciseLogs.size + 1}",
                        exerciseId = exercise.id,
                        exerciseName = exercise.name
                    )
                }
                val current = exerciseLogs.last()
                val note = cell(row, cols.note)
                val intensity = parseIntensity(cell(row, cols.intensity))
                exerciseLogs[exerciseLogs.lastIndex] = current.copy(
                    sets = current.sets + LoggedSet(
                        id = "imported-$number-${exerciseLogs.size}-${current.sets.size + 1}",
                        weight = weightLb,
                        reps = reps
                    ),
                    note = current.note.ifBlank { note },
                    intensity = current.intensity ?: intensity
                )
                lastName = name
                lastSet = setNumber
            }
            val workoutNote = rows.map { cell(it, cols.workoutNote) }.firstOrNull { it.isNotBlank() }.orEmpty()
            WorkoutLog(
                id = "imported-$number",
                startedAtMillis = startedAt,
                finishedAtMillis = startedAt + 60 * 60 * 1000L,
                templateName = "Workout $number",
                exercises = exerciseLogs,
                note = workoutNote
            )
        }
        return Plan(workouts, created, warnings)
    }

    private fun parseIntensity(text: String): Intensity? = when (text.trim().lowercase()) {
        "hard", "red" -> Intensity.HARD
        "ok", "okay", "yellow" -> Intensity.OK
        "easy", "green" -> Intensity.EASY
        else -> null
    }

    /** "2025-03-14" or an Excel serial number (days since 1899-12-30); null if neither. */
    private fun parseDate(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(t)?.let { m ->
            return at18h(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        val serial = t.toDoubleOrNull()
        if (serial != null && serial > 20_000 && serial < 80_000) {
            val cal = Calendar.getInstance().apply {
                clear()
                set(1899, Calendar.DECEMBER, 30, 18, 0, 0)
                add(Calendar.DAY_OF_YEAR, serial.toInt())
            }
            return cal.timeInMillis
        }
        return null
    }

    private fun at18h(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 18, 0, 0)
        }.timeInMillis

    private fun dayAt(nowMillis: Long, dayOffset: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---------------------------------------------------------------- reading the .xlsx file

    /** The first worksheet as a list of rows of text cells (blank where a cell is empty). */
    internal fun readSheet(input: InputStream): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    parts[name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        val sheetName = parts.keys.filter { it.startsWith("xl/worksheets/") }.minOrNull()
            ?: throw IllegalArgumentException("That doesn't look like an Excel (.xlsx) file.")
        val shared = parts["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let(::sharedStrings).orEmpty()
        return sheetRows(parts.getValue(sheetName).toString(Charsets.UTF_8), shared)
    }

    private fun sharedStrings(xml: String): List<String> =
        Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            // a string can be split into rich-text runs; phonetic hints (<rPh>) are not part of it
            val withoutPhonetic = si.groupValues[1].replace(Regex("""<rPh\b.*?</rPh>""", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(withoutPhonetic)
                .joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    private fun sheetRows(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (row in Regex("""<row\b[^>]*?(?:/>|>(.*?)</row>)""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val cells = HashMap<Int, String>()
            var width = 0
            for (c in Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1]
                val ref = Regex("""\br="([A-Z]+)\d+"""").find(attrs)?.groupValues?.get(1) ?: continue
                val type = Regex("""\bt="(\w+)"""").find(attrs)?.groupValues?.get(1)
                val body = c.groupValues[2]
                val text = when (type) {
                    "s" -> Regex("""<v>(.*?)</v>""").find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(body)
                        .joinToString("") { unescape(it.groupValues[1]) }
                    else -> Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape) ?: ""
                }
                val index = columnIndex(ref)
                cells[index] = text
                if (index + 1 > width) width = index + 1
            }
            rows += List(width) { cells[it].orEmpty() }
        }
        return rows
    }

    private fun columnIndex(letters: String): Int =
        letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
