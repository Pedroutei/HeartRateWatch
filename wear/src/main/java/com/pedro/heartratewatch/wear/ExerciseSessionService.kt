package com.pedro.heartratewatch.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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
 * is the best up-to-date reference to compare against. Pace here is deliberately NOT read from
 * Health Services' own DataType.PACE -- that field's unit isn't pinned down in this -rc build,
 * and getting it wrong would silently misfire every pace alert. Instead pace is computed locally
 * from a rolling window of DataType.DISTANCE_TOTAL samples, in units this file fully controls.
 *
 * Heart rate and pace alerting are independent (TrainingSettings.heartRateAlertsEnabled /
 * paceAlertsEnabled) -- either, both, or neither can be on. Each metric gets its own
 * [AlertChannel] (break/push-harder state, last-alert timestamp) so one metric's break countdown
 * doesn't block the other's from also firing. The one shared resource is the phone's alert audio
 * (AlertPlayer only ever plays one sound at a time) -- if both fire close together the second
 * message just replaces the first's audio, which is an acceptable simplification for a scaffold.
 *
 * Alerts are audio-only, played on the phone -- there is no on-watch vibration (removed as not
 * useful in practice; the watch's own screen and the phone's audio are enough).
 */
class ExerciseSessionService : LifecycleService() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var messageClient: MessageClient

    private val hrChannel = AlertChannel()
    private val paceChannel = AlertChannel()
    private var targetAlreadySent = false
    private var halfwayAlreadySent = false
    // Set when CalibrationActivity starts this service for a guided max-HR test, where
    // deliberately pushing above the normal upper threshold is the whole point -- without this,
    // a calibration run would trigger a spurious "break!" phone alert right as the user hits
    // their max effort. Sensor readings and distance still track normally either way. Also gates
    // run-history recording, since a calibration test isn't a training run.
    private var suppressAlerts = false

    private var sessionStartMillis = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private var maxBpmSeen = 0
    private var minBpmSeen = Int.MAX_VALUE
    private var paceSum = 0L
    private var paceCount = 0

    // Rolling (elapsed-time-millis, distanceMeters) samples over the last PACE_WINDOW_MILLIS,
    // used to derive a smoothed current pace rather than reacting to every noisy GPS tick.
    private val recentDistanceSamples = ArrayDeque<Pair<Long, Float>>()

    // Every distance sample for the whole run (not trimmed like recentDistanceSamples above),
    // used at the end to find real best-split times -- see bestSplitSeconds. A run's worth of
    // samples at roughly one per second is a few tens of KB at most, trivial to hold in memory.
    private val allDistanceSamples = mutableListOf<Pair<Long, Float>>()

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        calibrationStore = CalibrationStore(this)
        messageClient = Wearable.getMessageClient(this)
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
            distanceMeters = HeartRateRepository.state.value.distanceMeters,
            avgPaceSecPerKm = if (paceCount > 0) (paceSum / paceCount).toInt() else null,
            best1kmSeconds = bestSplitSeconds(1_000f),
            best5kmSeconds = bestSplitSeconds(5_000f),
            best10kmSeconds = bestSplitSeconds(10_000f)
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
            val latestPace = updateRollingPace(distanceMeters)
            if (!suppressAlerts) allDistanceSamples.add(System.currentTimeMillis() to distanceMeters)

            HeartRateRepository.update {
                it.copy(
                    currentBpm = latestBpm ?: it.currentBpm,
                    distanceMeters = distanceMeters,
                    currentPaceSecPerKm = latestPace ?: it.currentPaceSecPerKm
                )
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
            if (latestPace != null) {
                if (!suppressAlerts) {
                    paceSum += latestPace
                    paceCount++
                }
                lifecycleScope.launch { handlePace(latestPace) }
            }
            lifecycleScope.launch { checkDistanceProgress(distanceMeters) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) =
            Unit
    }

    /**
     * Derives a smoothed pace (seconds per km) from how far the oldest and newest samples in a
     * rolling window are apart, rather than reacting to every noisy per-update GPS jump. Returns
     * null until there's enough distance/time in the window for a stable reading (e.g. right at
     * the start of a run, or whenever GPS is off and distance barely moves).
     */
    private fun updateRollingPace(distanceMeters: Float): Int? {
        val now = System.currentTimeMillis()
        recentDistanceSamples.addLast(now to distanceMeters)
        while (recentDistanceSamples.isNotEmpty() &&
            now - recentDistanceSamples.first().first > PACE_WINDOW_MILLIS
        ) {
            recentDistanceSamples.removeFirst()
        }

        val oldest = recentDistanceSamples.firstOrNull() ?: return null
        val deltaMeters = distanceMeters - oldest.second
        val deltaMillis = now - oldest.first
        if (deltaMeters < MIN_PACE_SAMPLE_METERS || deltaMillis < MIN_PACE_SAMPLE_MILLIS) return null

        val secPerKm = (deltaMillis / 1000.0) / (deltaMeters / 1000.0)
        return secPerKm.toInt()
    }

    /**
     * Fastest actual time (seconds) to cover [targetMeters] anywhere within the run, via a
     * sliding window (two-pointer) over every distance sample recorded since it started. For
     * each sample index `endIndex`, shrinks the window from the left while it still covers at
     * least [targetMeters], tracking the smallest such window's elapsed time. O(n) since each
     * index only ever moves forward. Distance is monotonically non-decreasing (a running total),
     * so this is exact to sample granularity (Health Services updates roughly once a second) --
     * a little pessimistic versus a true continuous split, but close enough for a personal best,
     * not a substitute for a track and a stopwatch. Returns null if the run never covered that
     * far (e.g. GPS was off, or it was a short run).
     */
    private fun bestSplitSeconds(targetMeters: Float): Int? {
        if (allDistanceSamples.size < 2) return null
        var startIndex = 0
        var bestMillis = Long.MAX_VALUE
        for (endIndex in allDistanceSamples.indices) {
            while (allDistanceSamples[endIndex].second - allDistanceSamples[startIndex].second >= targetMeters) {
                val elapsed = allDistanceSamples[endIndex].first - allDistanceSamples[startIndex].first
                if (elapsed < bestMillis) bestMillis = elapsed
                startIndex++
            }
        }
        return if (bestMillis == Long.MAX_VALUE) null else (bestMillis / 1000).toInt()
    }

    /** True for [TrainingSettings.warmupSeconds] after the run starts -- see that field's doc. */
    private fun inWarmup(settings: TrainingSettings): Boolean =
        System.currentTimeMillis() - sessionStartMillis < settings.warmupSeconds * 1000L

    private suspend fun handleHeartRate(bpm: Int) {
        if (suppressAlerts) return
        val settings = settingsStore.settingsFlow.first()
        if (!settings.heartRateAlertsEnabled) return

        val maxHrBpm = settings.manualMaxHrBpm ?: calibrationStore.latestFlow.first()?.bpm
        val lower = settings.resolvedLowerBpm(maxHrBpm)
        val upper = settings.resolvedUpperBpm(maxHrBpm)

        // Heart rate has recovered back up to (or past) the lower threshold -- cut off any
        // push-harder alert audio still playing on the phone, regardless of which branch below
        // this reading falls into next.
        if (hrChannel.isPushHarderAlerting && bpm >= lower) {
            hrChannel.isPushHarderAlerting = false
            sendStopAlertToPhone()
        }

        when {
            bpm > upper -> triggerBreakAlert(hrChannel, settings, DataLayerPaths.ALERT_HIGH_HR) {
                HeartRateRepository.state.value.currentBpm?.let { handleHeartRate(it) }
            }
            bpm < lower && !inWarmup(settings) ->
                triggerPushHarderAlert(hrChannel, settings, DataLayerPaths.ALERT_LOW_HR)
            else -> Unit
        }
    }

    /**
     * Pace's relationship to effort is the mirror image of bpm's: a *lower* seconds-per-km value
     * means you're running *faster*, so going below the fastest-allowed threshold is the
     * "overdoing it, ease up" case (mirrors bpm > upper), and going above the slowest-allowed
     * threshold is the "underdoing it, push harder" case (mirrors bpm < lower).
     */
    private suspend fun handlePace(paceSecPerKm: Int) {
        if (suppressAlerts) return
        val settings = settingsStore.settingsFlow.first()
        if (!settings.paceAlertsEnabled) return

        val fastest = settings.fastestPaceSecPerKm
        val slowest = settings.slowestPaceSecPerKm

        if (paceChannel.isPushHarderAlerting && paceSecPerKm <= slowest) {
            paceChannel.isPushHarderAlerting = false
            sendStopAlertToPhone()
        }

        when {
            paceSecPerKm < fastest ->
                triggerBreakAlert(paceChannel, settings, DataLayerPaths.ALERT_PACE_TOO_FAST) {
                    HeartRateRepository.state.value.currentPaceSecPerKm?.let { handlePace(it) }
                }
            paceSecPerKm > slowest && !inWarmup(settings) ->
                triggerPushHarderAlert(paceChannel, settings, DataLayerPaths.ALERT_PACE_TOO_SLOW)
            else -> Unit
        }
    }

    /** Shared by both handleHeartRate (bpm > upper) and handlePace (too fast). */
    private fun triggerBreakAlert(
        channel: AlertChannel,
        settings: TrainingSettings,
        path: String,
        recheck: suspend () -> Unit
    ) {
        if (channel.isOnBreak) return
        // else: already inside a break countdown -- startBreakCountdown re-checks the latest
        // reading when its timer ends and restarts itself if still past the threshold, which is
        // what gives the "repeat until it comes down" behavior from the spec.
        channel.isOnBreak = true
        sendToPhone(path)
        HeartRateRepository.update {
            it.copy(onBreak = true, breakSecondsRemaining = settings.breakTimerSeconds)
        }
        startBreakCountdown(channel, settings.breakTimerSeconds, recheck)
    }

    /** Shared by both handleHeartRate (bpm < lower) and handlePace (too slow). */
    private fun triggerPushHarderAlert(channel: AlertChannel, settings: TrainingSettings, path: String) {
        val now = System.currentTimeMillis()
        if (now - channel.lastPushHarderAlertMillis < PUSH_HARDER_ALERT_INTERVAL_MS) return
        channel.lastPushHarderAlertMillis = now
        channel.isPushHarderAlerting = true
        sendToPhone(path)
    }

    private fun startBreakCountdown(channel: AlertChannel, breakTimerSeconds: Int, recheck: suspend () -> Unit) {
        lifecycleScope.launch {
            for (remaining in breakTimerSeconds downTo 0) {
                HeartRateRepository.update { it.copy(breakSecondsRemaining = remaining) }
                delay(1000)
            }
            channel.isOnBreak = false
            HeartRateRepository.update { it.copy(onBreak = false) }
            // Cut off the break alert audio the instant the timer runs out, rather than leaving
            // it to whatever the phone's default sound length is.
            sendStopAlertToPhone()
            // Re-check with the last known reading immediately, rather than waiting for the next
            // Health Services update, so still being over/under a threshold restarts the
            // countdown right away.
            recheck()
        }
    }

    private suspend fun checkDistanceProgress(distanceMeters: Float) {
        if (suppressAlerts) return
        val target = settingsStore.settingsFlow.first().distanceTargetMeters ?: return
        if (!halfwayAlreadySent && distanceMeters >= target / 2f) {
            halfwayAlreadySent = true
            sendToPhone(DataLayerPaths.ALERT_HALFWAY)
        }
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

    /** Per-metric alert state, so heart rate and pace can each be mid-break or mid-push-harder independently. */
    private class AlertChannel {
        var isOnBreak = false
        var isPushHarderAlerting = false
        var lastPushHarderAlertMillis = 0L
    }

    companion object {
        /** Intent extra: start this service without threshold-based alerts (see [suppressAlerts]). */
        const val EXTRA_SUPPRESS_ALERTS = "suppress_alerts"
        private const val NOTIFICATION_ID = 1
        private const val PUSH_HARDER_ALERT_INTERVAL_MS = 20_000L
        private const val PACE_WINDOW_MILLIS = 30_000L
        private const val MIN_PACE_SAMPLE_METERS = 5f
        private const val MIN_PACE_SAMPLE_MILLIS = 5_000L
    }
}
