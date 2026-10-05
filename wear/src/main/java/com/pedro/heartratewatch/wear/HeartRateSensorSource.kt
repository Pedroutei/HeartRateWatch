package com.pedro.heartratewatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Heart rate straight from the platform sensor, bypassing Health Services. On the Galaxy Watch 4
 * the Health Services exercise pipeline (and Samsung's sensor layer under it) goes silent while the
 * screen is off and only resumes when the watch is woken -- confirmed via Logcat. Samsung's own
 * guidance for continuous HR with the screen off is the *wake-up* variant of the sensor, which
 * makes the sensor hub wake the CPU for each event instead of batching until something else does;
 * ExerciseClient has no way to ask for that. Falls back to the regular sensor if this device has
 * no wake-up variant.
 */
class HeartRateSensorSource(
    context: Context,
    private val onBpm: (Int) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    /** Returns false if the device has no heart-rate sensor or registration was refused. */
    fun start(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            ?: return false
        Log.i(TAG, "Registering ${sensor.name} (wakeUp=${sensor.isWakeUpSensor})")
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 0 is the sensor's "no reading yet"; NO_CONTACT means it's off the wrist.
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        if (bpm <= 0 || event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return
        Log.i(TAG, "bpm=$bpm accuracy=${event.accuracy}")
        onBpm(bpm)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val TAG = "RawHR"
    }
}
