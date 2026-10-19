package com.cameraman.app

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs

private const val TOO_DARK_BRIGHTNESS = 45f
private const val BACKLIT_DIFFERENCE = 30f
private const val HARSH_HIGHLIGHT_FRACTION = 0.15f
private const val SIDE_LIGHT_DIFFERENCE = 45f
private const val MIN_FACE_WIDTH = 0.12f
private const val MAX_FACE_WIDTH = 0.5f
private const val GOOD_FACE_WIDTH = 0.22f
private const val ON_TARGET_DISTANCE = 0.08f

/** Zooming further than this makes photos blurry, so the coach asks to walk instead. */
private const val MAX_HELPFUL_ZOOM = 2f

/** Camera pointing more than this many degrees up or down gets a "tilt" tip. */
const val TILT_TOLERANCE_DEGREES = 12f

/** Tighter tilt limit for scenes with straight lines (buildings, horizons), so walls don't lean. */
private const val LEVEL_TOLERANCE_DEGREES = 5f

/** Face color cast above this counts as a green cast. Starting value; tune on a phone. */
private const val GREEN_CAST_TINT = 8f

/** Scenes where light shining toward the camera is the point (sunset glow, sun rays). */
private val backlightScenes = setOf(Scene.Sunset, Scene.SunRays)

/** Scenes where bright spots in the frame are expected (the sun, the moon, city lights). */
private val brightSpotScenes = setOf(Scene.Sunset, Scene.SunRays, Scene.Moon, Scene.NightCity)

enum class TipLevel { Info, Warning, Good }

enum class Side { Left, Right }

/**
 * Every tip the coach can show. Tips are drawn as icons; [meaning] is only shown in the "?" legend.
 */
enum class TipKind(val level: TipLevel, val meaning: String) {
    TooDark(TipLevel.Warning, "Too dark for this scene's recipe: move to more light or face a window"),
    Backlit(TipLevel.Warning, "Backlit: turn so the light is on the face"),
    HarshLight(TipLevel.Warning, "Harsh light: find shade or turn away from the sun"),
    GreenCast(TipLevel.Warning, "Green face: light bouncing off trees or grass, move into open light"),
    SideLight(TipLevel.Info, "Half the face in shadow: turn toward the yellow arrow"),
    StepCloser(TipLevel.Info, "Arrows pointing out: come closer"),
    StepBack(TipLevel.Info, "Arrows pointing in: step back"),
    ZoomIn(TipLevel.Info, "Magnifier +: zoom in (tap the icon to zoom)"),
    ZoomOut(TipLevel.Info, "Magnifier -: zoom out (tap the icon to zoom)"),
    TiltUp(TipLevel.Info, "Tilted phone, arrow up: point the camera up, hold it straight"),
    TiltDown(TipLevel.Info, "Tilted phone, arrow down: point the camera down, hold it straight"),
    RaisePhone(TipLevel.Info, "Phone with arrow up: hold the phone higher"),
    LowerPhone(TipLevel.Info, "Phone with arrow down: hold the phone lower"),
    LineUp(TipLevel.Info, "Move the face onto the green dot"),
    StandInBox(TipLevel.Info, "Stand inside the dashed box"),
    MoveSideways(TipLevel.Info, "Big arrow: move that way, into the box"),
    FixLimb(TipLevel.Info, "Red arm or leg: move it to match the outline"),
    LooksGood(TipLevel.Good, "Green frame and tick: take the shot")
}

/**
 * [target] is a screen point (0..1) to move the face onto; [side] is where the light comes from
 * (side light) or which way to move (sideways); [bone] is the limb to fix in pose mode;
 * [zoomTo] is the zoom ratio to set for zoom tips.
 */
data class Tip(
    val kind: TipKind,
    val target: PointF? = null,
    val side: Side? = null,
    val bone: Bone? = null,
    val zoomTo: Float? = null
)

/** The camera's current zoom ratio and the range it supports. */
data class ZoomInfo(val ratio: Float, val min: Float, val max: Float)

/**
 * Tip for making the subject [scale] times bigger (or smaller when below 1):
 * zoom when the camera can do it sharply, otherwise walk.
 */
fun sizeTip(scale: Float, zoom: ZoomInfo?): Tip {
    if (scale > 1f) {
        val target = zoom?.let { it.ratio * scale }
        val helpfulMax = zoom?.let { minOf(MAX_HELPFUL_ZOOM, it.max) }
        return if (target != null && helpfulMax != null && target <= helpfulMax) {
            Tip(TipKind.ZoomIn, zoomTo = target)
        } else {
            Tip(TipKind.StepCloser)
        }
    }
    // Zooming out helps as long as the camera is not already fully zoomed out.
    return if (zoom != null && zoom.ratio > zoom.min * 1.05f) {
        Tip(TipKind.ZoomOut, zoomTo = maxOf(zoom.ratio * scale, zoom.min))
    } else {
        Tip(TipKind.StepBack)
    }
}

/** Tip to hold the camera straight, or null when it already is. [pitchDegrees] > 0 = pointing down. */
fun tiltTip(pitchDegrees: Float?, toleranceDegrees: Float = TILT_TOLERANCE_DEGREES): Tip? = when {
    pitchDegrees == null -> null
    pitchDegrees > toleranceDegrees -> Tip(TipKind.TiltUp)
    pitchDegrees < -toleranceDegrees -> Tip(TipKind.TiltDown)
    else -> null
}

/** Tips for a scene without a face: hold level for buildings and horizons, then zoom as the recipe suggests. */
private fun sceneTip(scene: Scene?, zoom: ZoomInfo?, pitchDegrees: Float?): Tip? {
    if (scene == null) return null
    if (scene.keepLevel) tiltTip(pitchDegrees, LEVEL_TOLERANCE_DEGREES)?.let { return it }
    val sceneZoom = scene.zoom ?: return null
    if (zoom == null) return null
    val target = sceneZoom.coerceIn(zoom.min, zoom.max)
    return when {
        zoom.ratio < target * 0.9f -> Tip(TipKind.ZoomIn, zoomTo = target)
        zoom.ratio > target * 1.1f -> Tip(TipKind.ZoomOut, zoomTo = target)
        else -> null
    }
}

/**
 * Picks the single most useful tip for the current frame, or null when there is nothing to say.
 * [faceBox] is in screen coordinates (0..1 of the preview), already mirrored for the front camera,
 * and [faceLeftBrightness] / [faceRightBrightness] are the left and right halves of the face as seen on screen.
 * [tooDarkForScene] comes from the light meter (null when it has no reading yet: then brightness is used).
 */
fun chooseTip(
    frameBrightness: Float,
    highlightFraction: Float,
    faceBox: RectF?,
    faceBrightness: Float?,
    faceLeftBrightness: Float?,
    faceRightBrightness: Float?,
    zoom: ZoomInfo?,
    pitchDegrees: Float?,
    scene: Scene?,
    tooDarkForScene: Boolean?,
    faceTint: Float?
): Tip? {
    val tooDark = tooDarkForScene ?: (frameBrightness < TOO_DARK_BRIGHTNESS)
    if (tooDark && scene != Scene.Moon) {
        return Tip(TipKind.TooDark)
    }
    if (scene !in backlightScenes && faceBox != null && faceBrightness != null &&
        faceBrightness < frameBrightness - BACKLIT_DIFFERENCE
    ) {
        return Tip(TipKind.Backlit)
    }
    if (scene !in brightSpotScenes && highlightFraction > HARSH_HIGHLIGHT_FRACTION) {
        return Tip(TipKind.HarshLight)
    }
    if (faceBox != null && faceTint != null && faceTint > GREEN_CAST_TINT) {
        return Tip(TipKind.GreenCast)
    }
    if (faceBox != null && faceLeftBrightness != null && faceRightBrightness != null) {
        val difference = faceRightBrightness - faceLeftBrightness
        if (abs(difference) > SIDE_LIGHT_DIFFERENCE) {
            val lightSide = if (difference > 0) Side.Right else Side.Left
            return Tip(TipKind.SideLight, side = lightSide)
        }
    }
    if (faceBox == null) {
        // Light is fine and there is no face to frame: only the scene's own tips.
        return sceneTip(scene, zoom, pitchDegrees)
    }
    val tiltTolerance = if (scene?.keepLevel == true) LEVEL_TOLERANCE_DEGREES else TILT_TOLERANCE_DEGREES
    tiltTip(pitchDegrees, tiltTolerance)?.let { return it }
    if (faceBox.width() < MIN_FACE_WIDTH || faceBox.width() > MAX_FACE_WIDTH) {
        return sizeTip(GOOD_FACE_WIDTH / faceBox.width(), zoom)
    }

    // Eyes sit near the upper third line, on whichever vertical third line is closer.
    val target = PointF(if (faceBox.centerX() < 0.5f) 1f / 3 else 2f / 3, 1f / 3)
    val eyeLevel = faceBox.top + faceBox.height() * 0.4f
    // Lowering the phone moves the face up on screen, raising it moves the face down.
    if (eyeLevel - target.y > ON_TARGET_DISTANCE) return Tip(TipKind.LowerPhone, target = target)
    if (target.y - eyeLevel > ON_TARGET_DISTANCE) return Tip(TipKind.RaisePhone, target = target)
    if (abs(faceBox.centerX() - target.x) > ON_TARGET_DISTANCE) {
        return Tip(TipKind.LineUp, target = target)
    }
    return Tip(TipKind.LooksGood)
}
