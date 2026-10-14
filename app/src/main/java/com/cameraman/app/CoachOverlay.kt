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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
            // Mirroring swaps which half of the face is on the left of the screen.
            val leftOnScreen = if (isFrontCamera) it.faceRightBrightness else it.faceLeftBrightness
            val rightOnScreen = if (isFrontCamera) it.faceLeftBrightness else it.faceRightBrightness
            chooseTip(it.frameBrightness, it.highlightFraction, faceOnScreen, it.faceBrightness, leftOnScreen, rightOnScreen)
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
                val targetPoint = Offset(target.x * size.width, target.y * size.height)
                drawCircle(Color.Green, radius = 10.dp.toPx(), center = targetPoint)
                if (faceOnScreen != null) {
                    val facePoint = Offset(faceOnScreen.centerX() * size.width, faceOnScreen.centerY() * size.height)
                    drawArrow(from = facePoint, to = targetPoint, color = Color.Green)
                }
            }
            if (faceOnScreen != null && tip.lightSide != null) {
                // Arrow beside the face pointing toward the light.
                val faceCenter = Offset(faceOnScreen.centerX() * size.width, faceOnScreen.centerY() * size.height)
                val reach = faceOnScreen.width() * size.width
                val direction = if (tip.lightSide == Side.Right) 1f else -1f
                drawArrow(
                    from = faceCenter + Offset(direction * reach * 0.7f, 0f),
                    to = faceCenter + Offset(direction * reach * 1.4f, 0f),
                    color = Color.Yellow
                )
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

/** Draws a straight arrow with a small V-shaped head at [to]. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    val strokeWidth = 4.dp.toPx()
    drawLine(color, from, to, strokeWidth)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val headLength = 16.dp.toPx()
    for (wing in listOf(-0.5f, 0.5f)) {
        val wingAngle = angle + PI.toFloat() + wing
        drawLine(color, to, to + Offset(cos(wingAngle) * headLength, sin(wingAngle) * headLength), strokeWidth)
    }
}

/** Keeps showing the current tip until a different one has been chosen several frames in a row. */
class TipStabilizer {
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
