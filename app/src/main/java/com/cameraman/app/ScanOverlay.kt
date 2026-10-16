package com.cameraman.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val ON_TARGET_DEGREES = 15f

/**
 * Scan mode, drawn without words: a compass ring fills in as the user turns (and tilts up and down),
 * then every direction is colored green, yellow or red, a big arrow points to the best one,
 * and a tilt arrow shows whether to point the camera up or down there.
 * [target] is null while still scanning; [askingAi] is true while Gemini is choosing.
 */
@Composable
fun ScanOverlay(
    session: ScanSession,
    heading: Float?,
    pitchDegrees: Float?,
    directionScores: List<Float?>,
    target: ScanTarget?,
    askingAi: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFacingTarget = target != null && heading != null &&
        abs(turnDegrees(heading, target.headingDegrees)) <= ON_TARGET_DEGREES
    val tiltNeeded = if (isFacingTarget && target != null && pitchDegrees != null) {
        val currentBand = pitchBand(pitchDegrees)
        when {
            target.band.ordinal > currentBand.ordinal -> TipKind.TiltUp
            target.band.ordinal < currentBand.ordinal -> TipKind.TiltDown
            else -> null
        }
    } else {
        null
    }
    val sparkleRadius = pulsingRadius(base = 1f)

    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val stroke = 14.dp.toPx()
            val radius = size.minDimension / 2 - stroke
            val ringTopLeft = Offset(center.x - radius, center.y - radius)
            val ringSize = Size(radius * 2, radius * 2)
            val segment = 360f / DIRECTION_COUNT
            val knownScores = directionScores.filterNotNull()
            val lowestScore = knownScores.minOrNull() ?: 0f
            val scoreRange = ((knownScores.maxOrNull() ?: 0f) - lowestScore).coerceAtLeast(0.001f)

            /** One segment per direction. Up on screen = where the phone points now. */
            fun drawSegments(colorOf: (Int) -> Color) {
                for (direction in 0 until DIRECTION_COUNT) {
                    val startAngle = direction * segment - (heading ?: 0f) - 90f
                    drawArc(colorOf(direction), startAngle + 2f, segment - 4f, useCenter = false, topLeft = ringTopLeft, size = ringSize, style = Stroke(stroke))
                }
            }

            fun scoreColor(direction: Int): Color {
                val score = directionScores.getOrNull(direction) ?: return Color.White.copy(alpha = 0.2f)
                val share = (score - lowestScore) / scoreRange
                return when {
                    share > 0.66f -> GoodGreen
                    share > 0.33f -> LightYellow
                    else -> WarningRed
                }
            }

            when {
                heading == null -> {
                    // No motion sensor: crossed-out ring.
                    drawCircle(WarningRed, radius, center, style = Stroke(stroke / 2))
                    drawLine(WarningRed, center + Offset(-radius, radius) * 0.7f, center + Offset(radius, -radius) * 0.7f, stroke / 2, cap = StrokeCap.Round)
                }
                askingAi -> {
                    drawSegments(::scoreColor)
                    drawSparkle(center, radius * 0.5f * sparkleRadius, LightYellow)
                }
                target == null -> {
                    drawSegments { direction ->
                        if (session.isSeen(direction)) Color.White else Color.White.copy(alpha = 0.2f)
                    }
                    // Curved arrow in the middle: keep turning.
                    drawArc(Color.White, -60f, 240f, useCenter = false, topLeft = center - Offset(radius * 0.4f, radius * 0.4f), size = Size(radius * 0.8f, radius * 0.8f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    // Arrow head at the arc's end (left side), pointing up: keep turning clockwise.
                    val arrowTip = center - Offset(radius * 0.4f, 0f)
                    drawArrow(arrowTip + Offset(0f, 18.dp.toPx()), arrowTip, Color.White, 6.dp.toPx())
                    // Small up and down arrows on the right: also tilt up and down while turning.
                    val tiltX = center.x + radius * 0.65f
                    drawArrow(Offset(tiltX, center.y - 4.dp.toPx()), Offset(tiltX, center.y - radius * 0.35f), Color.White, 3.dp.toPx())
                    drawArrow(Offset(tiltX, center.y + 4.dp.toPx()), Offset(tiltX, center.y + radius * 0.35f), Color.White, 3.dp.toPx())
                }
                isFacingTarget && tiltNeeded != null -> {
                    drawSegments(::scoreColor)
                    drawTipIcon(tiltNeeded, center, radius)
                }
                isFacingTarget -> {
                    drawCircle(GoodGreen, radius, center, style = Stroke(stroke))
                    drawTipIcon(TipKind.LooksGood, center, radius)
                    if (target?.pickedByAi == true) drawSparkle(center + Offset(radius * 0.6f, -radius * 0.6f), radius * 0.2f, LightYellow)
                }
                else -> {
                    val turn = turnDegrees(heading, target.headingDegrees)
                    drawSegments(::scoreColor)
                    // Thin arc from "now" (top) to the best direction, and a big arrow that way.
                    val innerRadius = radius - stroke
                    drawArc(Color.White, -90f, turn, useCenter = false, topLeft = Offset(center.x - innerRadius, center.y - innerRadius), size = Size(innerRadius * 2, innerRadius * 2), style = Stroke(stroke / 3, cap = StrokeCap.Round))
                    val direction = if (turn > 0) 1f else -1f
                    drawArrow(center - Offset(direction * radius * 0.5f, 0f), center + Offset(direction * radius * 0.5f, 0f), Color.White, 10.dp.toPx())
                }
            }
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
        ) {
            Text("✕", color = Color.White, fontSize = 20.sp)
        }
    }
}

/** Four-pointed star: shown while Gemini is choosing, and when it chose the spot. */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val waist = radius * 0.25f
    val star = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + waist, center.y - waist)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + waist, center.y + waist)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - waist, center.y + waist)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - waist, center.y - waist)
        close()
    }
    drawPath(star, color)
}
