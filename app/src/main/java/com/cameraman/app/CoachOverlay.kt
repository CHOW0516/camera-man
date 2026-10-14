package com.cameraman.app

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How many frames in a row a new tip must win before it replaces the shown one (stops flicker). */
private const val FRAMES_BEFORE_TIP_CHANGE = 4

/** Draws the face box, the target dot and the current tip over the camera preview. */
@Composable
fun CoachOverlay(reading: FrameReading?, isFrontCamera: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val viewAspectRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
        val faceOnScreen = reading?.faceBox?.let {
            toScreenCoordinates(it, reading.imageAspectRatio, viewAspectRatio, isFrontCamera)
        }
        val newTip = reading?.let {
            chooseTip(it.frameBrightness, it.highlightFraction, faceOnScreen, it.faceBrightness)
        }
        val tipStabilizer = remember { TipStabilizer() }
        val tip = tipStabilizer.update(newTip) ?: return@BoxWithConstraints

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (faceOnScreen != null) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(faceOnScreen.left * size.width, faceOnScreen.top * size.height),
                    size = Size(faceOnScreen.width() * size.width, faceOnScreen.height() * size.height),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            tip.target?.let { target ->
                drawCircle(Color.Green, radius = 10.dp.toPx(), center = Offset(target.x * size.width, target.y * size.height))
            }
        }

        val bannerColor = when (tip.level) {
            TipLevel.Warning -> Color(0xCCB3261E)
            TipLevel.Good -> Color(0xCC1E7B34)
            TipLevel.Info -> Color(0x99000000)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
                .background(bannerColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(tip.message, color = Color.White, fontSize = 16.sp)
        }
    }
}

/** Keeps showing the current tip until a different one has been chosen several frames in a row. */
private class TipStabilizer {
    private var shownTip: Tip? = null
    private var candidateMessage: String? = null
    private var candidateCount = 0

    fun update(newTip: Tip?): Tip? {
        if (newTip == null) return shownTip
        val current = shownTip
        if (current == null || newTip.message == current.message) {
            // Same tip (the target dot may move), or nothing shown yet: show it now.
            shownTip = newTip
            candidateCount = 0
        } else if (newTip.message == candidateMessage) {
            candidateCount++
            if (candidateCount >= FRAMES_BEFORE_TIP_CHANGE) {
                shownTip = newTip
                candidateCount = 0
            }
        } else {
            candidateMessage = newTip.message
            candidateCount = 1
        }
        return shownTip
    }
}

/**
 * Maps a box from upright image coordinates to screen coordinates (both 0..1).
 * The preview fills the screen and crops the image's overflow evenly on both sides.
 */
private fun toScreenCoordinates(
    imageBox: RectF,
    imageAspectRatio: Float,
    viewAspectRatio: Float,
    isFrontCamera: Boolean
): RectF {
    // Visible part of the image, as a fraction of its width and height.
    val visibleWidth = if (imageAspectRatio > viewAspectRatio) viewAspectRatio / imageAspectRatio else 1f
    val visibleHeight = if (imageAspectRatio > viewAspectRatio) 1f else imageAspectRatio / viewAspectRatio
    val cropLeft = (1f - visibleWidth) / 2
    val cropTop = (1f - visibleHeight) / 2

    var left = (imageBox.left - cropLeft) / visibleWidth
    var right = (imageBox.right - cropLeft) / visibleWidth
    if (isFrontCamera) {
        // The front preview is shown mirrored, like a selfie.
        left = 1f - left
        right = 1f - right
    }
    return RectF(
        minOf(left, right),
        (imageBox.top - cropTop) / visibleHeight,
        maxOf(left, right),
        (imageBox.bottom - cropTop) / visibleHeight
    )
}
