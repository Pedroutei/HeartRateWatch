package com.pedro.heartratewatch.mobile

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Receives the "/alert/..." messages ExerciseSessionService sends from the watch the instant a
 * threshold is crossed, and plays the corresponding audio cue on the phone. Also receives the
 * "/calibration/result" message CalibrationStore sends when a guided max-HR test is saved, and
 * the "/run/summary" message sent when a run stops.
 */
class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == DataLayerPaths.ALERT_STOP) {
            AlertPlayer(applicationContext).stop()
            return
        }

        if (messageEvent.path == DataLayerPaths.CALIBRATION_RESULT) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            val bpm = buffer.int
            val recordedAtMillis = buffer.long
            CoroutineScope(Dispatchers.IO).launch {
                CalibrationRepository(applicationContext).save(CalibrationResult(bpm, recordedAtMillis))
            }
            return
        }

        if (messageEvent.path == DataLayerPaths.RUN_SUMMARY) {
            val summary = RunSummary.fromBytes(messageEvent.data)
            CoroutineScope(Dispatchers.IO).launch {
                RunHistoryRepository(applicationContext).addRun(summary)
            }
            return
        }

        val type = when (messageEvent.path) {
            DataLayerPaths.ALERT_HIGH_HR -> AlertType.HIGH_HR
            DataLayerPaths.ALERT_LOW_HR -> AlertType.LOW_HR
            DataLayerPaths.ALERT_PACE_TOO_SLOW -> AlertType.PACE_TOO_SLOW
            DataLayerPaths.ALERT_PACE_TOO_FAST -> AlertType.PACE_TOO_FAST
            DataLayerPaths.ALERT_TARGET_REACHED -> AlertType.TARGET_REACHED
            else -> return
        }
        AlertPlayer(applicationContext).play(type)
    }
}
