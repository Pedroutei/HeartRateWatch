package com.pedro.heartratewatch.wear

import com.pedro.heartratewatch.shared.TrainingSettings

/**
 * Whether a live reading is in range, too high (overdoing it -- the break-alert case), or too low
 * (underdoing it -- the push-harder-alert case). Used to color live bpm/pace readouts on the main
 * screen and the Tile, so effort is readable at a glance without doing threshold math in your
 * head. Null means "don't color this" -- no reading yet, or that metric's alerts are off, so
 * there's no threshold to judge it against.
 */
enum class EffortStatus { GOOD, TOO_HIGH, TOO_LOW }

fun heartRateStatus(bpm: Int?, settings: TrainingSettings, maxHrBpm: Int?): EffortStatus? {
    if (bpm == null || !settings.heartRateAlertsEnabled) return null
    val lower = settings.resolvedLowerBpm(maxHrBpm)
    val upper = settings.resolvedUpperBpm(maxHrBpm)
    return when {
        bpm > upper -> EffortStatus.TOO_HIGH
        bpm < lower -> EffortStatus.TOO_LOW
        else -> EffortStatus.GOOD
    }
}

/** Pace is inverted from bpm: a lower number is a faster pace, i.e. more effort. */
fun paceStatus(paceSecPerKm: Int?, settings: TrainingSettings): EffortStatus? {
    if (paceSecPerKm == null || !settings.paceAlertsEnabled) return null
    return when {
        paceSecPerKm < settings.fastestPaceSecPerKm -> EffortStatus.TOO_HIGH
        paceSecPerKm > settings.slowestPaceSecPerKm -> EffortStatus.TOO_LOW
        else -> EffortStatus.GOOD
    }
}
