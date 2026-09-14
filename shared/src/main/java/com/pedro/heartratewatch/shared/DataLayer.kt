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

    /** Sent watch -> phone when the session's distance target is reached. */
    const val ALERT_TARGET_REACHED = "/alert/target-reached"

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

enum class AlertType { HIGH_HR, LOW_HR, TARGET_REACHED }

/** Threshold input mode chosen by the user in Settings. */
enum class ThresholdMode { BPM, PERCENT_MAX_HR }

data class TrainingSettings(
    val lowerThresholdBpm: Int = 100,
    val upperThresholdBpm: Int = 160,
    val thresholdMode: ThresholdMode = ThresholdMode.BPM,
    val lowerThresholdPercent: Int = 60,
    val upperThresholdPercent: Int = 85,
    val breakTimerSeconds: Int = 30,
    val useGpsForDistance: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val distanceTargetMeters: Float? = null
) {
    // maxHrBpm comes from CalibrationStore/CalibrationRepository (a guided test result), not
    // from this settings model -- TrainingSettings only knows the percentages to apply to it.
    // Falls back to the literal bpm fields if no calibration exists yet, so switching to percent
    // mode before ever calibrating doesn't leave the threshold undefined.
    fun resolvedLowerBpm(maxHrBpm: Int?): Int = when (thresholdMode) {
        ThresholdMode.BPM -> lowerThresholdBpm
        ThresholdMode.PERCENT_MAX_HR -> maxHrBpm?.let { it * lowerThresholdPercent / 100 } ?: lowerThresholdBpm
    }

    fun resolvedUpperBpm(maxHrBpm: Int?): Int = when (thresholdMode) {
        ThresholdMode.BPM -> upperThresholdBpm
        ThresholdMode.PERCENT_MAX_HR -> maxHrBpm?.let { it * upperThresholdPercent / 100 } ?: upperThresholdBpm
    }
}