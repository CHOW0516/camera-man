package com.cameraman.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.pow

const val DIRECTION_COUNT = 12
private const val DEGREES_PER_DIRECTION = 360f / DIRECTION_COUNT
private const val MIN_FRAMES_PER_VIEW = 3

/** Directions that must be seen before a result is given (most of a full circle). */
const val DIRECTIONS_NEEDED = 10

/** Camera pointing more than this many degrees up or down counts as the "up" or "down" view. */
private const val BAND_DEGREES = 10f

// Starting weights for scoring a view; tune after real-world testing.
// Every part is scaled to 0..1 first, so the weights compare directly.
private const val LIGHT_WEIGHT = 1f
private const val BACKLIGHT_WEIGHT = 1f
private const val HIGHLIGHT_WEIGHT = 1f
private const val CLUTTER_WEIGHT = 2f
private const val PLAIN_WEIGHT = 0.8f
private const val SYMMETRY_WEIGHT = 0.6f
private const val LINES_WEIGHT = 0.6f
private const val RECIPE_LIGHT_WEIGHT = 1.5f

/** Average screen brightness auto exposure aims for (0..255). */
private const val MID_GREY = 118f

/** Which way the camera points up or down. Down = camera tilted toward the ground. */
enum class PitchBand { Down, Straight, Up }

fun pitchBand(pitchDegrees: Float): PitchBand = when {
    pitchDegrees > BAND_DEGREES -> PitchBand.Down
    pitchDegrees < -BAND_DEGREES -> PitchBand.Up
    else -> PitchBand.Straight
}

/** One scanned view: a direction around the circle plus up, straight or down. */
data class ScanView(val direction: Int, val band: PitchBand, val score: Float, val snapshot: Snapshot?) {
    val headingDegrees: Float get() = (direction + 0.5f) * DEGREES_PER_DIRECTION
}

/** Where scan mode tells the user to point the camera. [pickedByAi] is true when Gemini chose it. */
data class ScanTarget(val headingDegrees: Float, val band: PitchBand, val pickedByAi: Boolean)

/**
 * Collects what the camera sees while the user turns in a circle (12 directions, each split into
 * down, straight and up), then scores every view as a photo background.
 *
 * The subject stands with their back to the view, so the light on their face comes from the
 * opposite direction. A good view has light behind the photographer, no bright patches,
 * little clutter where the person will stand, and some structure: plain areas, straight lines, symmetry.
 */
class ScanSession {
    private val views = Array(DIRECTION_COUNT) { Array(PitchBand.entries.size) { ViewTotals() } }

    /** How many directions have been seen enough; observable from Compose. */
    var directionsSeen by mutableIntStateOf(0)
        private set

    fun record(headingDegrees: Float, pitchDegrees: Float, reading: FrameReading) {
        val direction = (headingDegrees / DEGREES_PER_DIRECTION).toInt().mod(DIRECTION_COUNT)
        views[direction][pitchBand(pitchDegrees).ordinal].add(reading)
        directionsSeen = (0 until DIRECTION_COUNT).count { isSeen(it) }
    }

    fun isSeen(direction: Int) = views[direction].any { it.frames >= MIN_FRAMES_PER_VIEW }

    val isComplete: Boolean get() = directionsSeen >= DIRECTIONS_NEEDED

    /**
     * Every view seen enough to score, best first. Empty until [isComplete].
     * With a [scene] and the [lockedMeter] reading (exposure is locked while scanning), views whose light is
     * too dim for the scene's recipe lose points.
     */
    fun ranking(scene: Scene?, lockedMeter: MeterReading?): List<ScanView> {
        if (!isComplete) return emptyList()
        val scored = mutableListOf<ScanView>()
        for (direction in 0 until DIRECTION_COUNT) {
            val opposite = (direction + DIRECTION_COUNT / 2) % DIRECTION_COUNT
            val lightOnSubject = directionBrightness(opposite) ?: continue
            for (band in PitchBand.entries) {
                val view = views[direction][band.ordinal]
                if (view.frames < MIN_FRAMES_PER_VIEW) continue
                val score = view.score(lightOnSubject) - RECIPE_LIGHT_WEIGHT * recipeDarkness(lightOnSubject, scene, lockedMeter)
                scored += ScanView(direction, band, score, view.snapshot)
            }
        }
        return scored.sortedByDescending { it.score }
    }

    /** Best score per direction (null when not seen), for coloring the ring. */
    fun directionScores(ranking: List<ScanView>): List<Float?> =
        (0 until DIRECTION_COUNT).map { direction -> ranking.firstOrNull { it.direction == direction }?.score }

    /** 0 when the light on the subject is enough for the recipe, up to 1 when it needs 4× the recipe's ISO or more. */
    private fun recipeDarkness(lightOnSubject: Float, scene: Scene?, lockedMeter: MeterReading?): Float {
        if (scene == null || lockedMeter == null) return 0f
        // Screen brightness follows light roughly to the power 1/2.2, so undo that to compare amounts of light.
        val lightRatio = (lightOnSubject / MID_GREY).pow(2.2f).coerceAtLeast(0.01f)
        val isoNeeded = scene.isoNeeded(lockedMeter) / lightRatio
        return (log2(isoNeeded / scene.maxIso) / 2f).coerceIn(0f, 1f)
    }

    /** Average brightness of a direction over all its views, or null if not seen. */
    private fun directionBrightness(direction: Int): Float? {
        val seen = views[direction].filter { it.frames > 0 }
        if (seen.isEmpty() || !isSeen(direction)) return null
        return seen.sumOf { it.brightness.toDouble() }.toFloat() / seen.sumOf { it.frames }
    }
}

/** Running totals for one view. Keeps the first snapshot taken there. */
private class ViewTotals {
    var frames = 0
    var brightness = 0f
    private var highlight = 0f
    private var subjectClutter = 0f
    private var plain = 0f
    private var symmetry = 0f
    private var lines = 0f
    var snapshot: Snapshot? = null
        private set

    fun add(reading: FrameReading) {
        frames++
        brightness += reading.frameBrightness
        highlight += reading.highlightFraction
        subjectClutter += reading.subjectClutter
        plain += reading.plainFraction
        symmetry += reading.symmetry
        lines += reading.lineStrength
        if (snapshot == null) snapshot = reading.snapshot
    }

    fun score(lightOnSubject: Float): Float {
        val background = brightness / frames
        val light = (lightOnSubject / 160f).coerceIn(0f, 1f)
        val backlight = ((background - lightOnSubject) / 100f).coerceIn(0f, 1f)
        val highlights = (highlight / frames * 3f).coerceIn(0f, 1f)
        val clutter = (subjectClutter / frames / 25f).coerceIn(0f, 1f)
        val plainAreas = plain / frames
        // Even messy views are about 70% symmetric on this scale, so only the part above counts.
        val mirrored = ((symmetry / frames - 0.7f) / 0.25f).coerceIn(0f, 1f)
        val straightLines = lines / frames
        return LIGHT_WEIGHT * light - BACKLIGHT_WEIGHT * backlight - HIGHLIGHT_WEIGHT * highlights -
            CLUTTER_WEIGHT * clutter + PLAIN_WEIGHT * plainAreas + SYMMETRY_WEIGHT * mirrored +
            LINES_WEIGHT * straightLines
    }
}

/** Degrees to turn from [currentHeading] to [targetHeading]: positive = turn right, -180..180. */
fun turnDegrees(currentHeading: Float, targetHeading: Float): Float =
    ((targetHeading - currentHeading + 540f) % 360f) - 180f
