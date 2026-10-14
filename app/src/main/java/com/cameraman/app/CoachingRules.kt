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
 * [target] is a screen point (0..1) to move the face onto; [lightSide] is the side of the
 * screen the light comes from, drawn as an arrow.
 */
data class Tip(
    val message: String,
    val level: TipLevel,
    val target: PointF? = null,
    val lightSide: Side? = null
)

/**
 * Picks the single most useful tip for the current frame.
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
): Tip {
    if (frameBrightness < TOO_DARK_BRIGHTNESS) {
        return Tip("Too dark. Move to more light or face a window.", TipLevel.Warning)
    }
    if (faceBox != null && faceBrightness != null && faceBrightness < frameBrightness - BACKLIT_DIFFERENCE) {
        return Tip("Backlit. Turn so the light falls on the face.", TipLevel.Warning)
    }
    if (highlightFraction > HARSH_HIGHLIGHT_FRACTION) {
        return Tip("Harsh light. Try shade, or turn away from the sun.", TipLevel.Warning)
    }
    if (faceBox != null && faceLeftBrightness != null && faceRightBrightness != null) {
        val difference = faceRightBrightness - faceLeftBrightness
        if (abs(difference) > SIDE_LIGHT_DIFFERENCE) {
            val lightSide = if (difference > 0) Side.Right else Side.Left
            return Tip(
                "Half the face is in shadow. Turn the face toward the light.",
                TipLevel.Info,
                lightSide = lightSide
            )
        }
    }
    if (faceBox == null) {
        return Tip("Light looks good. Point at a person for framing tips.", TipLevel.Info)
    }
    if (faceBox.width() < MIN_FACE_WIDTH) {
        return Tip("Step closer.", TipLevel.Info)
    }
    if (faceBox.top < MIN_HEADROOM) {
        return Tip("Leave a little space above the head.", TipLevel.Info)
    }

    // Eyes sit near the upper third line, on whichever vertical third line is closer.
    val target = PointF(if (faceBox.centerX() < 0.5f) 1f / 3 else 2f / 3, 1f / 3)
    val eyeLevel = faceBox.top + faceBox.height() * 0.4f
    val distance = hypot(faceBox.centerX() - target.x, eyeLevel - target.y)
    if (distance > ON_TARGET_DISTANCE) {
        return Tip("Line up the face with the green dot.", TipLevel.Info, target)
    }
    return Tip("Looks good! Take the shot.", TipLevel.Good)
}
