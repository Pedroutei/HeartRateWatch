package com.pedro.heartratewatch.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.RunSummary
import com.pedro.heartratewatch.shared.TrainingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Foreground service that owns the Health Services ExerciseClient session for the duration of a
 * run. This is deliberately a *foreground* service (with the persistent notification Android
 * requires once you declare FOREGROUND_SERVICE_TYPE_HEALTH) rather than the batched
 * PassiveMonitoringClient, because the whole point of this app is near-real-time threshold
 * alerts -- see the project's requirements.md doc, "Technical approach" section, for why.
 *
 * NOTE FOR PEDRO: this is the most API-surface-heavy file in the scaffold. Health Services'
 * exact class/method names have shifted release to release more than the rest of Jetpack (the
 * dependency below is still an -rc build), so if something here doesn't compile, Android
 * Studio's quick-fix (Alt+Enter / the light bulb) will usually point you at the renamed
 * equivalent, and Google's own "android/health-samples" repo on GitHub (ExerciseSampleCompose)
 * is the best up-to-date reference to compare against.
 */
class ExerciseSessionService : LifecycleService() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var messageClient: MessageClient
    private var vibrator: Vibrator? = null

    private var isOnBreak = false
    private var isLowHrAlerting = false
    private var targetAlreadySent = false
    private var lastLowHrAlertMillis = 0L
    // Set when CalibrationActivity starts this service for a guided max-HR test, where
    // deliberately pushing above the normal upper threshold is the whole point -- without this,
    // a calibration run would trigger a spurious "break!" vibration/phone alert right as the
    // user hits their max effort. Sensor readings and distance still track normally either way.
    // Also gates run-history recording, since a calibration test isn't a training run.
    private var suppressAlerts = false

    private var sessionStartMillis = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private var maxBpmSeen = 0
    private var minBpmSeen = Int.MAX_VALUE

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        calibrationStore = CalibrationStore(this)
        messageClient = Wearable.getMessageClient(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        sessionStartMillis = System.currentTimeMillis()

        startForeground(NOTIFICATION_ID, buildNotification())
        exerciseClient.setUpdateCallback(exerciseUpdateCallback)
        lifecycleScope.launch { startExercise() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_SUPPRESS_ALERTS, false) == true) {
            suppressAlerts = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        lifecycleScope.launch { runCatching { exerciseClient.endExerciseAsync().await() } }
        sendStopAlertToPhone()
        if (!suppressAlerts) sendRunSummaryToPhone()
        HeartRateRepository.update { it.copy(isActive = false, onBreak = false) }
        super.onDestroy()
    }

    /**
     * Tells the phone to cut off any alert audio still playing from a break/push-harder cue.
     * Uses its own scope rather than lifecycleScope, since super.onDestroy() below cancels
     * lifecycleScope and this needs to actually finish sending.
     */
    private fun sendStopAlertToPhone() {
        CoroutineScope(Dispatchers.IO).launch {
            val nodes = runCatching {
                Wearable.getNodeClient(this@ExerciseSessionService).connectedNodes.await()
            }.getOrNull().orEmpty()

            nodes.forEach { node ->
                messageClient.sendMessage(node.id, DataLayerPaths.ALERT_STOP, ByteArray(0))
            }
        }
    }

    /** Same independent-scope reasoning as [sendStopAlertToPhone] -- must outlive onDestroy(). */
    private fun sendRunSummaryToPhone() {
        if (hrCount == 0) return
        val summary = RunSummary(
            startedAtMillis = sessionStartMillis,
            durationSeconds = ((System.currentTimeMillis() - sessionStartMillis) / 1000).toInt(),
            avgBpm = (hrSum / hrCount).toInt(),
            maxBpm = maxBpmSeen,
            minBpm = minBpmSeen,
            distanceMeters = HeartRateRepository.state.value.distanceMeters
        )
        CoroutineScope(Dispatchers.IO).launch {
            val nodes = runCatching {
                Wearable.getNodeClient(this@ExerciseSessionService).connectedNodes.await()
            }.getOrNull().orEmpty()

            nodes.forEach { node ->
                messageClient.sendMessage(node.id, DataLayerPaths.RUN_SUMMARY, summary.toBytes())
            }
        }
    }

    private suspend fun startExercise() {
        val settings = settingsStore.settingsFlow.first()

        val config = ExerciseConfig(
            exerciseType = ExerciseType.RUNNING,
            dataTypes = setOf(DataType.HEART_RATE_BPM, DataType.DISTANCE_TOTAL),
            isAutoPauseAndResumeEnabled = false,
            isGpsEnabled = settings.useGpsForDistance
        )

        exerciseClient.startExerciseAsync(config).await()
        HeartRateRepository.update { it.copy(isActive = true) }
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) = Unit
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val heartRatePoints = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            val latestBpm = heartRatePoints.lastOrNull()?.value?.toInt()

            val distancePoint = update.latestMetrics.getData(DataType.DISTANCE_TOTAL)
            val distanceMeters = distancePoint?.total?.toFloat()
                ?: HeartRateRepository.state.value.distanceMeters

            HeartRateRepository.update {
                it.copy(currentBpm = latestBpm ?: it.currentBpm, distanceMeters = distanceMeters)
            }

            if (latestBpm != null) {
                if (!suppressAlerts) {
                    hrSum += latestBpm
                    hrCount++
                    if (latestBpm > maxBpmSeen) maxBpmSeen = latestBpm
                    if (latestBpm < minBpmSeen) minBpmSeen = latestBpm
                }
                lifecycleScope.launch { handleHeartRate(latestBpm) }
            }
            lifecycleScope.launch { checkDistanceTarget(distanceMeters) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) =
            Unit
    }

    private suspend fun handleHeartRate(bpm: Int) {
        if (suppressAlerts) return
        val settings = settingsStore.settingsFlow.first()
        val maxHrBpm = calibrationStore.latestFlow.first()?.bpm
        val lower = settings.resolvedLowerBpm(maxHrBpm)
        val upper = settings.resolvedUpperBpm(maxHrBpm)

        // Heart rate has recovered back up to (or past) the lower threshold -- cut off any
        // push-harder alert audio still playing on the phone, regardless of which branch below
        // this reading falls into next.
        if (isLowHrAlerting && bpm >= lower) {
            isLowHrAlerting = false
            sendStopAlertToPhone()
        }

        when {
            bpm > upper -> {
                if (!isOnBreak) {
                    isOnBreak = true
                    alertLocally(settings, HIGH_HR_VIBRATION_PATTERN)
                    sendToPhone(DataLayerPaths.ALERT_HIGH_HR)
                    HeartRateRepository.update {
                        it.copy(onBreak = true, breakSecondsRemaining = settings.breakTimerSeconds)
                    }
                    startBreakCountdown(settings.breakTimerSeconds)
                }
                // else: already inside a break countdown -- startBreakCountdown re-checks the
                // latest bpm when its timer ends and restarts itself if still above `upper`,
                // which is what gives the "repeat until it comes down" behavior from the spec.
            }

            bpm < lower -> {
                val now = System.currentTimeMillis()
                if (now - lastLowHrAlertMillis >= LOW_HR_ALERT_INTERVAL_MS) {
                    lastLowHrAlertMillis = now
                    isLowHrAlerting = true
                    alertLocally(settings, LOW_HR_VIBRATION_PATTERN)
                    sendToPhone(DataLayerPaths.ALERT_LOW_HR)
                }
            }

            else -> Unit
        }
    }

    private fun startBreakCountdown(seconds: Int) {
        lifecycleScope.launch {
            for (remaining in seconds downTo 0) {
                HeartRateRepository.update { it.copy(breakSecondsRemaining = remaining) }
                delay(1000)
            }
            isOnBreak = false
            HeartRateRepository.update { it.copy(onBreak = false) }
            // Cut off the break alert audio the instant the timer runs out, rather than leaving
            // it to whatever the phone's default sound length is.
            sendStopAlertToPhone()
            // Re-check with the last known reading immediately, rather than waiting for the
            // next Health Services update, so a still-high heart rate restarts the break
            // countdown right away.
            HeartRateRepository.state.value.currentBpm?.let { bpm -> handleHeartRate(bpm) }
        }
    }

    private suspend fun checkDistanceTarget(distanceMeters: Float) {
        if (suppressAlerts) return
        val target = settingsStore.settingsFlow.first().distanceTargetMeters ?: return
        if (!targetAlreadySent && distanceMeters >= target) {
            targetAlreadySent = true
            sendToPhone(DataLayerPaths.ALERT_TARGET_REACHED)
        }
    }

    /** Fire-and-forget message to every currently connected node (in practice, the phone). */
    private fun sendToPhone(path: String) {
        lifecycleScope.launch {
            val nodes = runCatching {
                Wearable.getNodeClient(this@ExerciseSessionService).connectedNodes.await()
            }.getOrNull().orEmpty()

            nodes.forEach { node -> messageClient.sendMessage(node.id, path, ByteArray(0)) }
        }
    }

    private fun alertLocally(settings: TrainingSettings, pattern: LongArray) {
        if (!settings.vibrationEnabled) return
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun buildNotification(): Notification {
        val channelId = "exercise_session"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Run session", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tracking your run")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Intent extra: start this service without threshold-based alerts (see [suppressAlerts]). */
        const val EXTRA_SUPPRESS_ALERTS = "suppress_alerts"
        private const val NOTIFICATION_ID = 1
        private const val LOW_HR_ALERT_INTERVAL_MS = 20_000L
        private val HIGH_HR_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)
        private val LOW_HR_VIBRATION_PATTERN = longArrayOf(0, 150, 150, 150, 150, 150)
    }
}
