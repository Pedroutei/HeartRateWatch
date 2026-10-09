package com.pedro.heartratewatch.mobile

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the workout history as an Excel (.xlsx) sheet, one row per set, in the same layout
 * [WorkoutImporter] reads -- so an export can be edited in Excel and imported back, or used as a
 * backup. The extra "Workout ID" column is what makes importing an export back onto the same phone
 * replace the original workouts instead of duplicating them. Pure Kotlin, no Android classes.
 */
object WorkoutExporter {

    val HEADERS = listOf(
        "Workout", "Date", "Exercise", "Set", "Weight (lb)", "Reps",
        "Exercise note", "Intensity", "Workout note", "Workout ID"
    )

    /**
     * Each workout's number: an imported workout keeps the number it was imported with, and the
     * rest are numbered after the highest of those, oldest first.
     */
    fun numbering(workouts: List<WorkoutLog>): Map<String, Int> {
        val ordered = workouts.sortedBy { it.startedAtMillis }
        val imported = { w: WorkoutLog -> w.id.removePrefix("imported-").takeIf { w.id.startsWith("imported-") }?.toIntOrNull() }
        var next = (ordered.mapNotNull(imported).maxOrNull() ?: 0) + 1
        return ordered.associate { it.id to (imported(it) ?: next++) }
    }

    /** Workouts oldest first, numbered by [numbering]. */
    fun write(workouts: List<WorkoutLog>, out: OutputStream) {
        val ordered = workouts.sortedBy { it.startedAtMillis }
        val numbers = numbering(workouts)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val rows = mutableListOf<List<Any?>>()
        for (workout in ordered) {
            val number = numbers.getValue(workout.id)
            val date = dateFormat.format(Date(workout.startedAtMillis))
            var first = true
            for (log in workout.exercises) {
                if (log.sets.isEmpty()) {
                    rows += rowOf(number, date, log.exerciseName, null, null, null, log, 0, workout, first)
                    first = false
                    continue
                }
                log.sets.forEachIndexed { index, set ->
                    rows += rowOf(number, date, log.exerciseName, set.weight, set.reps, index + 1, log, index, workout, first)
                    first = false
                }
            }
            if (first && workout.note.isNotBlank()) {
                rows += listOf(number, date, "", null, null, null, "", "", workout.note, workout.id)
            }
        }
        writeXlsx(listOf<List<Any?>>(HEADERS) + rows, out)
    }

    private fun rowOf(
        number: Int, date: String, exercise: String, weight: Double?, reps: Int?, setNumber: Int?,
        log: ExerciseLog, indexInExercise: Int, workout: WorkoutLog, firstOfWorkout: Boolean
    ): List<Any?> = listOf(
        number, date, exercise, setNumber,
        weight?.let { Math.round(it * 100) / 100.0 },
        reps,
        if (indexInExercise == 0) log.note else "",
        if (indexInExercise == 0) log.intensity?.label.orEmpty() else "",
        if (firstOfWorkout) workout.note else "",
        workout.id
    )

    // ---------------------------------------------------------------- the .xlsx container

    private fun writeXlsx(rows: List<List<Any?>>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", WORKBOOK)
            part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            part("xl/styles.xml", STYLES)
            part("xl/worksheets/sheet1.xml", sheetXml(rows))
        }
    }

    private fun sheetXml(rows: List<List<Any?>>): String {
        val widths = listOf(9, 17, 32, 5, 12, 6, 42, 10, 28, 40)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        rows.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                val ref = "${columnLetters(c)}${r + 1}"
                val style = if (r == 0) """ s="1"""" else ""
                when (value) {
                    null -> {}
                    is Int, is Long -> sb.append("""<c r="$ref"$style><v>$value</v></c>""")
                    is Double -> sb.append("""<c r="$ref"$style><v>${trimNumber(value)}</v></c>""")
                    else -> {
                        val text = value.toString()
                        if (text.isNotEmpty()) {
                            sb.append("""<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${escape(text)}</t></is></c>""")
                        }
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun columnLetters(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** Escapes XML and drops characters XML can't hold at all (control characters). */
    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\n' && ch != '\t' -> {}
                else -> append(ch)
            }
        }
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Workouts" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
