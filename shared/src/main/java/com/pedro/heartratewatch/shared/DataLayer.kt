package com.pedro.heartratewatch.shared

/**
 * Shared constants and models for communication between the wear app and the mobile companion
 * app over the Wear OS Data Layer (MessageClient / DataClient). Keeping these in one module
 * that both :wear and :mobile depend on means the two sides can never drift apart on path or
 * key spelling.
 */
object DataLayerPaths {
    /** Sent watch -> phone the instant the high-HR (break) threshold is crossed. */
    const val ALERT_HIGH_HR = "/alert/high-hr"

    /** Sent watch -> phone the instant the low-HR (push-harder) threshold is crossed. */
    const val ALERT_LOW_HR = "/alert/low-hr"

    /** Sent watch -> phone the instant pace drops below the slowest-allowed threshold. */
    const val ALERT_PACE_TOO_SLOW = "/alert/pace-too-slow"

    /** Sent watch -> phone the instant pace goes above the fastest-allowed threshold. */
    const val ALERT_PACE_TOO_FAST = "/alert/pace-too-fast"

    /** Sent watch -> phone when the session's distance target is reached. */
    const val ALERT_TARGET_REACHED = "/alert/target-reached"

    /** Sent watch -> phone when half of the session's distance target has been covered. */
    const val ALERT_HALFWAY = "/alert/halfway"

    /** Sent watch -> phone the instant the run is stopped, to cut off any alert audio. */
    const val ALERT_STOP = "/alert/stop"

    /** Pushed phone -> watch (as a DataClient item, not a Message) whenever settings change. */
    const val SETTINGS_SYNC = "/settings/sync"

    /**
     * Sent watch -> phone when a guided max-HR calibration test (see CalibrationActivity on
     * :wear) is saved, so the result is visible on the phone too. Payload is a 12-byte buffer:
     * a big-endian Int (bpm) followed by a big-endian Long (recorded-at, epoch millis).
     */
    const val CALIBRATION_RESULT = "/calibration/result"

    /**
     * Sent watch -> phone when a run stops (see ExerciseSessionService), so run history is
     * browsable on the phone. Not sent for calibration test sessions. Payload is
     * RunSummary.toBytes()/fromBytes().
     */
    const val RUN_SUMMARY = "/run/summary"
}

enum class AlertType { HIGH_HR, LOW_HR, PACE_TOO_SLOW, PACE_TOO_FAST, TARGET_REACHED, HALFWAY }

/** Threshold input mode chosen by the user in Settings. */
enum class ThresholdMode { BPM, PERCENT_MAX_HR }

data class TrainingSettings(
    // Independent, not mutually exclusive -- either, both, or neither can be on, so
    // ExerciseSessionService's handleHeartRate/handlePace each gate on their own flag rather
    // than a single shared "which metric" choice.
    val heartRateAlertsEnabled: Boolean = true,
    val paceAlertsEnabled: Boolean = false,
    val lowerThresholdBpm: Int = 100,
    val upperThresholdBpm: Int = 160,
    val thresholdMode: ThresholdMode = ThresholdMode.BPM,
    val lowerThresholdPercent: Int = 60,
    val upperThresholdPercent: Int = 85,
    // Manual override for max HR -- takes precedence over the guided calibration result when
    // set. Null means "no override, use the calibration."
    val manualMaxHrBpm: Int? = null,
    // Pace, in whole seconds per kilometer. A *lower* value is a *faster* pace, which is the
    // opposite relationship bpm has to effort -- see resolvedLowerBpm/resolvedUpperBpm for the
    // bpm side, and ExerciseSessionService.handlePace for how these map to alert types.
    val fastestPaceSecPerKm: Int = 240,
    val slowestPaceSecPerKm: Int = 420,
    // Push-harder-style alerts (LOW_HR, PACE_TOO_SLOW) are suppressed for this many seconds
    // after a run starts, since everyone starts below their target effort before warming up.
    // Break-style alerts (HIGH_HR, PACE_TOO_FAST) are never suppressed by this.
    val warmupSeconds: Int = 30,
    val breakTimerSeconds: Int = 30,
    val useGpsForDistance: Boolean = false,
    val distanceTargetMeters: Float? = null,
    // If Strava is installed on the watch, bring it to the foreground the instant "Start run" is
    // tapped (see MainActivity.RunScreen on :wear) -- there's no public Strava API/intent to
    // actually start recording remotely, so this just saves swiping to find the app; you still
    // have to tap Record inside Strava yourself.
    val launchStravaOnStart: Boolean = false
) {
    // maxHrBpm comes from CalibrationStore/CalibrationRepository (a guided test result) or the
    // manualMaxHrBpm override above, not from this settings model -- TrainingSettings only knows
    // the percentages to apply to it. Falls back to the literal bpm fields if no calibration (or
    // override) exists yet, so switching to percent mode before ever calibrating doesn't leave
    // the threshold undefined.
    fun resolvedLowerBpm(maxHrBpm: Int?): Int = when (thresholdMode) {
        ThresholdMode.BPM -> lowerThresholdBpm
        ThresholdMode.PERCENT_MAX_HR -> maxHrBpm?.let { it * lowerThresholdPercent / 100 } ?: lowerThresholdBpm
    }

    fun resolvedUpperBpm(maxHrBpm: Int?): Int = when (thresholdMode) {
        ThresholdMode.BPM -> upperThresholdBpm
        ThresholdMode.PERCENT_MAX_HR -> maxHrBpm?.let { it * upperThresholdPercent / 100 } ?: upperThresholdBpm
    }
}