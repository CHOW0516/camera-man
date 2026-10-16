package com.cameraman.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** How many frames in a row a new tip must win before it replaces the shown one (stops flicker). */
private const val FRAMES_BEFORE_TIP_CHANGE = 4

/** Draws the face box, the target dot and the current tip over the camera preview. */
@Composable
fun CoachOverlay(
    reading: FrameReading?,
    isFrontCamera: Boolean,
    zoom: ZoomInfo?,
    pitchDegrees: Float?,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val viewAspectRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
        val faceOnScreen = reading?.faceBox?.let {
            toScreenCoordinates(it, reading.imageAspectRatio, viewAspectRatio, isFrontCamera)
        }
        val newTip = reading?.let {
            // Mirroring swaps which half of the face is on the left of the screen.
            val leftOnScreen = if (isFrontCamera) it.faceRightBrightness else it.faceLeftBrightness
            val rightOnScreen = if (isFrontCamera) it.faceLeftBrightness else it.faceRightBrightness
            chooseTip(
                it.frameBrightness, it.highlightFraction, faceOnScreen, it.faceBrightness,
                leftOnScreen, rightOnScreen, zoom, pitchDegrees
            )
        }
        val tipStabilizer = remember { TipStabilizer() }
        val tip = tipStabilizer.update(newTip, hasReading = reading != null)
        BuzzWhenGood(tip?.kind)
        val dotRadius = pulsingRadius(base = with(LocalDensity.current) { 10.dp.toPx() })

        Canvas(modifier = Modifier.fillMaxSize()) {
            val faceRect = faceOnScreen?.let {
                Rect(it.left * size.width, it.top * size.height, it.right * size.width, it.bottom * size.height)
            }
            if (faceRect != null) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = faceRect.topLeft,
                    size = faceRect.size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            when (tip?.kind) {
                TipKind.LineUp -> tip?.target?.let { target ->
                    val targetPoint = Offset(target.x * size.width, target.y * size.height)
                    drawCircle(GoodGreen, radius = dotRadius, center = targetPoint)
                    if (faceRect != null) drawArrow(from = faceRect.center, to = targetPoint, color = GoodGreen)
                }
                TipKind.SideLight -> if (faceRect != null && tip?.side != null) {
                    // Arrow beside the face pointing toward the light.
                    val direction = if (tip?.side == Side.Right) 1f else -1f
                    drawArrow(
                        from = faceRect.center + Offset(direction * faceRect.width * 0.7f, 0f),
                        to = faceRect.center + Offset(direction * faceRect.width * 1.4f, 0f),
                        color = LightYellow
                    )
                }
                TipKind.StepCloser -> if (faceRect != null) drawCornerArrows(faceRect, pointInward = false)
                TipKind.StepBack -> if (faceRect != null) drawCornerArrows(faceRect, pointInward = true)
                TipKind.RaisePhone, TipKind.LowerPhone -> tip?.target?.let { target ->
                    // Dashed line where the eyes should be, and an arrow showing where the face will move.
                    val lineY = target.y * size.height
                    drawLine(
                        GoodGreen, Offset(0f, lineY), Offset(size.width, lineY), 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f))
                    )
                    if (faceRect != null) {
                        val eyePoint = Offset(faceRect.center.x, faceRect.top + faceRect.height * 0.4f)
                        drawArrow(eyePoint, Offset(eyePoint.x, lineY), GoodGreen, 6.dp.toPx())
                    }
                }
                TipKind.LooksGood -> drawSuccessFrame()
                else -> tip?.let { drawCameraMoveHint(it, zoom) }
            }
        }

        if (tip != null) {
            TipBadge(
                kind = tip.kind,
                onClick = tip.zoomTo?.let { ratio -> { onZoom(ratio) } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            )
        }
    }
}

/** Keeps showing the current tip until a different one has been chosen several frames in a row. */
class TipStabilizer {
    private var shownTip: Tip? = null
    private var candidateKind: TipKind? = null
    private var candidateCount = 0

    /**
     * [newTip] null with [hasReading] true means "nothing to say", which also has to win
     * several frames before the shown tip disappears.
     */
    fun update(newTip: Tip?, hasReading: Boolean): Tip? {
        if (!hasReading) return shownTip
        val current = shownTip
        if (newTip?.kind == current?.kind) {
            // Same tip (the target dot may move): show the latest one now.
            shownTip = newTip
            candidateCount = 0
        } else if (newTip?.kind == candidateKind) {
            candidateCount++
            if (candidateCount >= FRAMES_BEFORE_TIP_CHANGE) {
                shownTip = newTip
                candidateCount = 0
            }
        } else {
            candidateKind = newTip?.kind
            candidateCount = 1
        }
        return shownTip
    }
}
