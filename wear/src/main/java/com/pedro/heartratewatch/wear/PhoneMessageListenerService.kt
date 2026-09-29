package com.pedro.heartratewatch.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pedro.heartratewatch.shared.DataLayerPaths

/**
 * Receives phone -> watch commands sent as Messages (as opposed to SettingsSyncListenerService,
 * which handles the phone's DataItem-based settings sync). Currently just
 * DataLayerPaths.START_CALIBRATION.
 */
class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != DataLayerPaths.START_CALIBRATION) return

        val launchIntent = Intent(applicationContext, CalibrationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Try opening it directly first -- works on plenty of real Wear OS setups, and means
        // glancing at the watch is enough, no tap needed. Not guaranteed on every OS version
        // though (background-activity-launch restrictions), so a notification always follows as
        // a fallback way in regardless of whether the direct launch actually got through.
        runCatching { applicationContext.startActivity(launchIntent) }
        postFallbackNotification(launchIntent)
    }

    private fun postFallbackNotification(launchIntent: Intent) {
        val channelId = "calibration_trigger"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Calibration", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Max HR calibration")
            .setContentText("Tap to start the guided test")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}
