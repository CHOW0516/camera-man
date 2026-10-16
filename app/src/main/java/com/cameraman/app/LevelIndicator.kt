package com.cameraman.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LEVEL_TOLERANCE_DEGREES = 1.5f
private const val SMOOTHING = 0.15f

/** Minimum sideways gravity (m/s²) to trust the reading; below this the phone is lying flat. */
private const val MIN_UPRIGHT_GRAVITY = 3f

/**
 * How the phone is held. [sideDegrees]: side tilt, 0 = level horizon.
 * [pitchDegrees]: 0 = camera points straight ahead, positive = camera points down, negative = up.
 */
data class PhoneTilt(val sideDegrees: Float, val pitchDegrees: Float)

/**
 * A line across the middle of the screen that tilts with the phone.
 * White while tilted, green when the horizon is level.
 */
@Composable
fun LevelIndicator(tilt: PhoneTilt?, modifier: Modifier = Modifier) {
    val tiltDegrees = tilt?.sideDegrees ?: return
    val isLevel = abs(tiltDegrees) < LEVEL_TOLERANCE_DEGREES

    Canvas(modifier = modifier) {
        val lineColor = if (isLevel) Color.Green else Color.White
        val halfLength = size.width * 0.2f
        val strokeWidth = 3.dp.toPx()
        rotate(degrees = -tiltDegrees, pivot = center) {
            drawLine(
                lineColor,
                Offset(center.x - halfLength, center.y),
                Offset(center.x + halfLength, center.y),
                strokeWidth
            )
        }
    }
}

/** Returns how the phone is held, or null when it is lying flat or has no sensor. */
@Composable
fun rememberPhoneTilt(): PhoneTilt? {
    val context = LocalContext.current
    var phoneTilt by remember { mutableStateOf<PhoneTilt?>(null) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gravityX = event.values[0]
                val gravityY = event.values[1]
                val gravityZ = event.values[2]
                val uprightGravity = sqrt(gravityX * gravityX + gravityY * gravityY)
                if (uprightGravity < MIN_UPRIGHT_GRAVITY) {
                    phoneTilt = null
                    return
                }
                val rawAngle = Math.toDegrees(atan2(gravityX, gravityY).toDouble()).toFloat()
                // Measure tilt from the nearest upright or sideways position.
                val side = rawAngle - (rawAngle / 90f).roundToInt() * 90f
                // Screen turned toward the sky means the back camera points down.
                val pitch = Math.toDegrees(atan2(gravityZ, uprightGravity).toDouble()).toFloat()
                val previous = phoneTilt
                phoneTilt = if (previous == null) {
                    PhoneTilt(side, pitch)
                } else {
                    PhoneTilt(
                        previous.sideDegrees + SMOOTHING * (side - previous.sideDegrees),
                        previous.pitchDegrees + SMOOTHING * (pitch - previous.pitchDegrees)
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    return phoneTilt
}
