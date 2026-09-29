package com.pedro.heartratewatch.shared

import kotlin.math.roundToInt

/**
 * Units for displaying or entering a distance value. Lives in :shared (rather than just the phone
 * app) because it's now part of [TrainingSettings] and synced to the watch, so the tile/watch
 * screen format pace and distance the same way the phone does.
 */
enum class DistanceUnit(val symbol: String, val metersPerUnit: Double) {
    KILOMETERS("km", 1000.0),
    MILES("mi", 1609.344),
    METERS("meters", 1.0),
    FEET("feet", 0.3048),
    YARDS("yards", 0.9144),
    FOOTBALL_FIELDS("football fields", 91.44),
    BANANAS("bananas", 0.18)
}

/**
 * Units offered for pace specifically, deliberately narrower than [DistanceUnit] as a whole --
 * pace as "time per banana" doesn't produce a meaningful number (a marathon pace is a tiny
 * fraction of a second per banana), so only the two units runners actually pace themselves by are
 * offered here.
 */
val PACE_UNITS = listOf(DistanceUnit.KILOMETERS, DistanceUnit.MILES)

/** Formats a pace stored canonically as whole seconds-per-kilometer into "M:SS /<unit>". */
fun formatPace(secPerKm: Int, unit: DistanceUnit): String {
    val secPerUnit = (secPerKm * (unit.metersPerUnit / 1000.0)).roundToInt()
    return "%d:%02d /%s".format(secPerUnit / 60, secPerUnit % 60, unit.symbol)
}

/**
 * Formats a distance stored canonically in meters into "<value> <unit>", e.g. "5.23 km". Whole
 * numbers for the larger fixed units (meters/feet/yards), where a decimal just adds noise.
 */
fun formatDistance(meters: Float, unit: DistanceUnit): String {
    val value = meters / unit.metersPerUnit
    val decimals = when (unit) {
        DistanceUnit.METERS, DistanceUnit.FEET, DistanceUnit.YARDS -> 0
        else -> 2
    }
    return "%.${decimals}f %s".format(value, unit.symbol)
}
