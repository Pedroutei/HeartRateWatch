package com.pedro.heartratewatch.shared

import java.nio.ByteBuffer

/**
 * Snapshot of one completed training run, sent watch -> phone (DataLayerPaths.RUN_SUMMARY) when
 * ExerciseSessionService stops, so run history is browsable on the phone's bigger screen without
 * the watch needing its own history UI. Not sent for calibration test sessions.
 */
data class RunSummary(
    val startedAtMillis: Long,
    val durationSeconds: Int,
    val avgBpm: Int,
    val maxBpm: Int,
    val minBpm: Int,
    val distanceMeters: Float,
    // Seconds per kilometer, or null if the run never had a stable pace reading (e.g. GPS was
    // off, or the run was too short/stationary for a rolling window to fill).
    val avgPaceSecPerKm: Int? = null,
    // Fastest actual time (seconds) to cover each distance anywhere within the run, from a
    // sliding window over every distance sample recorded (see
    // ExerciseSessionService.bestSplitSeconds) -- null if the run never covered that far.
    val best1kmSeconds: Int? = null,
    val best5kmSeconds: Int? = null,
    val best10kmSeconds: Int? = null
) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(BYTE_SIZE)
        .putLong(startedAtMillis)
        .putInt(durationSeconds)
        .putInt(avgBpm)
        .putInt(maxBpm)
        .putInt(minBpm)
        .putFloat(distanceMeters)
        // 0 is used as the "no data" sentinel rather than a real value in all of these, since a
        // genuine zero is physically impossible for any of them.
        .putInt(avgPaceSecPerKm ?: 0)
        .putInt(best1kmSeconds ?: 0)
        .putInt(best5kmSeconds ?: 0)
        .putInt(best10kmSeconds ?: 0)
        .array()

    companion object {
        const val BYTE_SIZE = 8 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4

        fun fromBytes(bytes: ByteArray): RunSummary {
            val buffer = ByteBuffer.wrap(bytes)
            return RunSummary(
                startedAtMillis = buffer.long,
                durationSeconds = buffer.int,
                avgBpm = buffer.int,
                maxBpm = buffer.int,
                minBpm = buffer.int,
                distanceMeters = buffer.float,
                avgPaceSecPerKm = buffer.int.takeIf { it > 0 },
                best1kmSeconds = buffer.int.takeIf { it > 0 },
                best5kmSeconds = buffer.int.takeIf { it > 0 },
                best10kmSeconds = buffer.int.takeIf { it > 0 }
            )
        }
    }
}
