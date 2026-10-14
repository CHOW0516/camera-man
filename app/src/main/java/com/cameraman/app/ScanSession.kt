package com.cameraman.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max

private const val DIRECTION_COUNT = 12
private const val DEGREES_PER_DIRECTION = 360f / DIRECTION_COUNT
private const val MIN_FRAMES_PER_DIRECTION = 3

/** Directions that must be seen before a result is given (most of a full circle). */
const val DIRECTIONS_NEEDED = 10

// Starting weights for scoring a direction; tune after real-world testing.
private const val BACKLIGHT_PENALTY = 1.5f
private const val HIGHLIGHT_PENALTY = 200f
private const val CLUTTER_PENALTY = 2f

/**
 * Collects what the camera sees while the user turns in a circle, split into 12 directions,
 * then picks the direction to shoot toward.
 *
 * For each direction the subject would stand with their back to it, so the light on their face
 * comes from the opposite direction. A good direction has bright light behind the photographer,
 * a background that is not brighter than that light, no blown-out patches, and little clutter.
 */
class ScanSession {
    private val frameCount = IntArray(DIRECTION_COUNT)
    private val brightnessTotal = FloatArray(DIRECTION_COUNT)
    private val highlightTotal = FloatArray(DIRECTION_COUNT)
    private val clutterTotal = FloatArray(DIRECTION_COUNT)

    /** How many directions have been seen enough; observable from Compose. */
    var directionsSeen by mutableIntStateOf(0)
        private set

    fun record(headingDegrees: Float, reading: FrameReading) {
        val direction = (headingDegrees / DEGREES_PER_DIRECTION).toInt().mod(DIRECTION_COUNT)
        frameCount[direction]++
        brightnessTotal[direction] += reading.frameBrightness
        highlightTotal[direction] += reading.highlightFraction
        clutterTotal[direction] += reading.clutter
        directionsSeen = frameCount.count { it >= MIN_FRAMES_PER_DIRECTION }
    }

    /** The heading (degrees) of the best direction, or null if too little was seen. */
    fun bestHeading(): Float? {
        if (directionsSeen < DIRECTIONS_NEEDED) return null
        var bestDirection: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (direction in 0 until DIRECTION_COUNT) {
            val opposite = (direction + DIRECTION_COUNT / 2) % DIRECTION_COUNT
            if (!isSeen(direction) || !isSeen(opposite)) continue

            val lightOnSubject = average(brightnessTotal, opposite)
            val background = average(brightnessTotal, direction)
            val score = lightOnSubject -
                BACKLIGHT_PENALTY * max(0f, background - lightOnSubject) -
                HIGHLIGHT_PENALTY * average(highlightTotal, direction) -
                CLUTTER_PENALTY * average(clutterTotal, direction)
            if (score > bestScore) {
                bestScore = score
                bestDirection = direction
            }
        }
        return bestDirection?.let { (it + 0.5f) * DEGREES_PER_DIRECTION }
    }

    private fun isSeen(direction: Int) = frameCount[direction] >= MIN_FRAMES_PER_DIRECTION

    private fun average(totals: FloatArray, direction: Int) = totals[direction] / frameCount[direction]
}

/** Degrees to turn from [currentHeading] to [targetHeading]: positive = turn right, -180..180. */
fun turnDegrees(currentHeading: Float, targetHeading: Float): Float =
    ((targetHeading - currentHeading + 540f) % 360f) - 180f
