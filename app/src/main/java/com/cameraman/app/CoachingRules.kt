package com.cameraman.app

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

private const val TOO_DARK_BRIGHTNESS = 45f
private const val BACKLIT_DIFFERENCE = 30f
private const val HARSH_HIGHLIGHT_FRACTION = 0.15f
private const val SIDE_LIGHT_DIFFERENCE = 45f
private const val MIN_FACE_WIDTH = 0.12f
private const val MIN_HEADROOM = 0.04f
private const val ON_TARGET_DISTANCE = 0.08f

enum class TipLevel { Info, Warning, Good }

enum class Side { Left, Right }

/**
 * Every tip the coach can show. Tips are drawn as icons; [meaning] is only shown in the "?" legend.
 */
enum class TipKind(val level: TipLevel, val meaning: String) {
    TooDark(TipLevel.Warning, "Too dark: move to more light or face a window"),
    Backlit(TipLevel.Warning, "Backlit: turn so the light is on the face"),
    HarshLight(TipLevel.Warning, "Harsh light: find shade or turn away from the sun"),
    SideLight(TipLevel.Info, "Half the face in shadow: turn toward the yellow arrow"),
    StepCloser(TipLevel.Info, "Arrows pointing out: come closer"),
    StepBack(TipLevel.Info, "Arrows pointing in: step back"),
    NeedHeadroom(TipLevel.Info, "Arrow down at the top: leave space above the head"),
    LineUp(TipLevel.Info, "Move the face onto the green dot"),
    StandInBox(TipLevel.Info, "Stand inside the dashed box"),
    MoveSideways(TipLevel.Info, "Big arrow: move that way, into the box"),
    FixLimb(TipLevel.Info, "Red arm or leg: move it to match the outline"),
    LooksGood(TipLevel.Good, "Green frame and tick: take the shot")
}

/**
 * [target] is a screen point (0..1) to move the face onto; [side] is where the light comes from
 * (side light) or which way to move (sideways); [bone] is the limb to fix in pose mode.
 */
data class Tip(
    val kind: TipKind,
    val target: PointF? = null,
    val side: Side? = null,
    val bone: Bone? = null
)

/**
 * Picks the single most useful tip for the current frame, or null when there is nothing to say.
 * [faceBox] is in screen coordinates (0..1 of the preview), already mirrored for the front camera,
 * and [faceLeftBrightness] / [faceRightBrightness] are the left and right halves of the face as seen on screen.
 */
fun chooseTip(
    frameBrightness: Float,
    highlightFraction: Float,
    faceBox: RectF?,
    faceBrightness: Float?,
    faceLeftBrightness: Float?,
    faceRightBrightness: Float?
): Tip? {
    if (frameBrightness < TOO_DARK_BRIGHTNESS) {
        return Tip(TipKind.TooDark)
    }
    if (faceBox != null && faceBrightness != null && faceBrightness < frameBrightness - BACKLIT_DIFFERENCE) {
        return Tip(TipKind.Backlit)
    }
    if (highlightFraction > HARSH_HIGHLIGHT_FRACTION) {
        return Tip(TipKind.HarshLight)
    }
    if (faceBox != null && faceLeftBrightness != null && faceRightBrightness != null) {
        val difference = faceRightBrightness - faceLeftBrightness
        if (abs(difference) > SIDE_LIGHT_DIFFERENCE) {
            val lightSide = if (difference > 0) Side.Right else Side.Left
            return Tip(TipKind.SideLight, side = lightSide)
        }
    }
    if (faceBox == null) {
        // Light is fine and there is no face to frame: nothing to show.
        return null
    }
    if (faceBox.width() < MIN_FACE_WIDTH) {
        return Tip(TipKind.StepCloser)
    }
    if (faceBox.top < MIN_HEADROOM) {
        return Tip(TipKind.NeedHeadroom)
    }

    // Eyes sit near the upper third line, on whichever vertical third line is closer.
    val target = PointF(if (faceBox.centerX() < 0.5f) 1f / 3 else 2f / 3, 1f / 3)
    val eyeLevel = faceBox.top + faceBox.height() * 0.4f
    val distance = hypot(faceBox.centerX() - target.x, eyeLevel - target.y)
    if (distance > ON_TARGET_DISTANCE) {
        return Tip(TipKind.LineUp, target = target)
    }
    return Tip(TipKind.LooksGood)
}
