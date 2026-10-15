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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val ON_TARGET_DEGREES = 15f

/**
 * Scan mode, drawn without words: a compass ring fills in as the user turns, then a big arrow
 * points to the best direction and the ring turns green when the phone faces it.
 * [bestHeading] is null while still scanning.
 */
@Composable
fun ScanOverlay(
    session: ScanSession,
    heading: Float?,
    bestHeading: Float?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnTarget = bestHeading != null && heading != null &&
        abs(turnDegrees(heading, bestHeading)) <= ON_TARGET_DEGREES

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

            when {
                heading == null -> {
                    // No motion sensor: crossed-out ring.
                    drawCircle(WarningRed, radius, center, style = Stroke(stroke / 2))
                    drawLine(WarningRed, center + Offset(-radius, radius) * 0.7f, center + Offset(radius, -radius) * 0.7f, stroke / 2, cap = StrokeCap.Round)
                }
                bestHeading == null -> {
                    // One segment per direction, filled once seen. Up on screen = where the phone points now.
                    for (direction in 0 until DIRECTION_COUNT) {
                        val color = if (session.isSeen(direction)) Color.White else Color.White.copy(alpha = 0.2f)
                        val startAngle = direction * segment - heading - 90f
                        drawArc(color, startAngle + 2f, segment - 4f, useCenter = false, topLeft = ringTopLeft, size = ringSize, style = Stroke(stroke))
                    }
                    // Curved arrow in the middle: keep turning.
                    drawArc(Color.White, -60f, 240f, useCenter = false, topLeft = center - Offset(radius * 0.4f, radius * 0.4f), size = Size(radius * 0.8f, radius * 0.8f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    // Arrow head at the arc's end (left side), pointing up: keep turning clockwise.
                    val arrowTip = center - Offset(radius * 0.4f, 0f)
                    drawArrow(arrowTip + Offset(0f, 18.dp.toPx()), arrowTip, Color.White, 6.dp.toPx())
                }
                isOnTarget -> {
                    drawCircle(GoodGreen, radius, center, style = Stroke(stroke))
                    drawTipIcon(TipKind.LooksGood, center, radius)
                }
                else -> {
                    val turn = turnDegrees(heading, bestHeading)
                    // Arc from "now" (top) to the best direction, and a big arrow that way.
                    drawCircle(Color.White.copy(alpha = 0.2f), radius, center, style = Stroke(stroke))
                    drawArc(LightYellow, -90f, turn, useCenter = false, topLeft = ringTopLeft, size = ringSize, style = Stroke(stroke, cap = StrokeCap.Round))
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
