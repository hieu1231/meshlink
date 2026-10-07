package com.hieu.p2p.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs

/**
 * Compass heading (degrees from true-ish north, magnetic is close enough at walking scale),
 * smoothed, corrected for screen rotation, and only running while its owner is started.
 */
class HeadingSensor(
    context: Context,
    /** true when the sensor reports low/unreliable accuracy, i.e. it wants a figure-8 calibration. */
    private val onAccuracy: (Boolean) -> Unit = {},
    private val onHeading: (Float?) -> Unit,
) : DefaultLifecycleObserver, SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)
    private var smoothed: Float? = null

    val available: Boolean get() = sensor != null

    override fun onStart(owner: LifecycleOwner) {
        if (sensor == null) {
            onHeading(null)
            return
        }
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        // The magnetometer is what drifts; watch its accuracy too.
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onStop(owner: LifecycleOwner) {
        sensors.unregisterListener(this)
        smoothed = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION")
        val rotation = window.defaultDisplay.rotation
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        SensorManager.getOrientation(remapped, angles)
        val raw = ((Math.toDegrees(angles[0].toDouble()) + 360) % 360).toFloat()
        val prev = smoothed
        // Low-pass across the 359/0 seam.
        val next = if (prev == null) raw else {
            var delta = raw - prev
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            if (abs(delta) < 0.5f) return
            (prev + delta * 0.15f + 360) % 360
        }
        smoothed = next
        onHeading(next)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        onAccuracy(accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
    }
}
