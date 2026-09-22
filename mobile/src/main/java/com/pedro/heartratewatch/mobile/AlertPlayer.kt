package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import com.pedro.heartratewatch.shared.AlertType

/**
 * Plays the alert audio on the PHONE when the watch reports a threshold crossing. Because the
 * phone is what's actually connected to your headphones/AirPods, this is what makes "play a
 * break alert in my headset while the app runs on my watch" work -- see the project's
 * requirements.md doc, "Cross-device audio" section, for the reasoning.
 *
 * Custom sounds are stored and played from here (the phone), not synced to the watch -- that
 * avoids Wear OS's more limited on-watch storage entirely, and matches where the audio actually
 * plays anyway. Each alert type has a bundled default (res/raw) used until a custom sound is set.
 */
class AlertPlayer(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("custom_sounds", Context.MODE_PRIVATE)

    /** Call after the user picks an audio file via a document picker for a given alert type. */
    fun setCustomSound(type: AlertType, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.edit().putString(type.name, uri.toString()).apply()
    }

    fun clearCustomSound(type: AlertType) {
        prefs.edit().remove(type.name).apply()
    }

    fun play(type: AlertType) {
        val customUriString = prefs.getString(type.name, null)
        val uri = customUriString?.let(Uri::parse)
            ?: Uri.parse("android.resource://${context.packageName}/${defaultSoundResId(type)}")

        runCatching {
            stop()
            currentPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnCompletionListener { it.release(); currentPlayer = null }
                setOnErrorListener { mp, _, _ -> mp.release(); currentPlayer = null; true }
                prepare()
                start()
            }
        }
    }

    /** Stops and releases whatever alert sound is currently playing, if any. */
    fun stop() {
        currentPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        currentPlayer = null
    }

    private fun defaultSoundResId(type: AlertType): Int = when (type) {
        AlertType.HIGH_HR -> R.raw.alert_high_hr
        AlertType.LOW_HR -> R.raw.alert_low_hr
        AlertType.PACE_TOO_FAST -> R.raw.alert_pace_too_fast
        AlertType.PACE_TOO_SLOW -> R.raw.alert_pace_too_slow
        AlertType.TARGET_REACHED -> R.raw.alert_target_reached
        AlertType.HALFWAY -> R.raw.alert_halfway
    }

    companion object {
        // A new AlertPlayer is constructed per incoming message (see
        // WearMessageListenerService), so this has to be shared across instances for stop() to
        // be able to find whatever play() most recently started.
        @Volatile
        private var currentPlayer: MediaPlayer? = null
    }
}
