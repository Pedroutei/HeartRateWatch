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
    val distanceMeters: Float
) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(BYTE_SIZE)
        .putLong(startedAtMillis)
        .putInt(durationSeconds)
        .putInt(avgBpm)
        .putInt(maxBpm)
        .putInt(minBpm)
        .putFloat(distanceMeters)
        .array()

    companion object {
        const val BYTE_SIZE = 8 + 4 + 4 + 4 + 4 + 4

        fun fromBytes(bytes: ByteArray): RunSummary {
            val buffer = ByteBuffer.wrap(bytes)
            return RunSummary(
                startedAtMillis = buffer.long,
                durationSeconds = buffer.int,
                avgBpm = buffer.int,
                maxBpm = buffer.int,
                minBpm = buffer.int,
                distanceMeters = buffer.float
            )
        }
    }
}
