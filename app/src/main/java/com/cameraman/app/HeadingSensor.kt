package com.cameraman.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The direction the back camera points, in degrees (0..360, growing as you turn right).
 * Uses the game rotation sensor, which does not need a compass, so 0 is just where it started.
 * Holds null when the phone has no rotation sensor. Only listens while [enabled].
 */
@Composable
fun rememberHeadingDegrees(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotationMatrix = FloatArray(9)
        val cameraMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Phone held upright: measure where the camera (the phone's back) points.
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, cameraMatrix
                )
                SensorManager.getOrientation(cameraMatrix, orientation)
                val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                heading.value = (degrees + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        if (enabled && sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            heading.value = null
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return heading
}
