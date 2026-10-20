package com.cameraman.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Bottom bar buttons. [meaning] is shown only in the "?" legend. */
enum class ControlIcon(val meaning: String) {
    Filter("Color filters"),
    Grid("Grid lines on or off"),
    Scan("Scout: walk around; the app keeps the 3 best views (tap one to find it again)"),
    Pose("Pose guide on or off"),
    Flip("Switch front and back camera")
}

/** Round icon button. Yellow when [active]. */
@Composable
fun ControlButton(icon: ControlIcon, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (active) LightYellow else Color.White
    Canvas(
        modifier = modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick)
    ) {
        drawControlIcon(icon, center, size.minDimension * 0.55f, color)
    }
}

fun DrawScope.drawControlIcon(icon: ControlIcon, center: Offset, iconSize: Float, color: Color) {
    val half = iconSize / 2
    val stroke = iconSize * 0.09f
    val topLeft = center - Offset(half, half)
    when (icon) {
        ControlIcon.Filter -> {
            // Three see-through circles: red, green, blue.
            val radius = iconSize * 0.3f
            val spread = iconSize * 0.2f
            drawCircle(Color.Red.copy(alpha = 0.7f), radius, center + Offset(0f, -spread))
            drawCircle(Color.Green.copy(alpha = 0.7f), radius, center + Offset(-spread, spread * 0.7f))
            drawCircle(Color.Blue.copy(alpha = 0.7f), radius, center + Offset(spread, spread * 0.7f))
            drawCircle(color, iconSize * 0.55f, center, style = Stroke(stroke * 0.6f))
        }
        ControlIcon.Grid -> {
            drawRect(color, topLeft, Size(iconSize, iconSize), style = Stroke(stroke))
            for (step in 1..2) {
                val offset = iconSize * step / 3
                drawLine(color, Offset(topLeft.x + offset, topLeft.y), Offset(topLeft.x + offset, topLeft.y + iconSize), stroke * 0.7f)
                drawLine(color, Offset(topLeft.x, topLeft.y + offset), Offset(topLeft.x + iconSize, topLeft.y + offset), stroke * 0.7f)
            }
        }
        ControlIcon.Scan -> {
            // Compass ring with a needle and a turning arrow at the top.
            drawArc(color, -60f, 300f, false, topLeft, Size(iconSize, iconSize), style = Stroke(stroke, cap = StrokeCap.Round))
            val arrowTip = center + Offset(half * 0.5f, -half * 0.87f)
            drawArrowHead(arrowTip, Offset(1f, 0.6f), iconSize * 0.25f, color, stroke)
            val needle = Path().apply {
                moveTo(center.x, center.y - half * 0.6f)
                lineTo(center.x + half * 0.2f, center.y)
                lineTo(center.x, center.y + half * 0.6f)
                lineTo(center.x - half * 0.2f, center.y)
                close()
            }
            drawPath(needle, color)
        }
        ControlIcon.Pose -> {
            // Stick figure with one arm up.
            val headCenter = center + Offset(0f, -half * 0.7f)
            drawCircle(color, iconSize * 0.13f, headCenter)
            val neck = center + Offset(0f, -half * 0.42f)
            val hip = center + Offset(0f, half * 0.25f)
            drawLine(color, neck, hip, stroke, StrokeCap.Round)
            drawLine(color, neck, center + Offset(-half * 0.6f, -half * 0.9f), stroke, StrokeCap.Round)
            drawLine(color, neck, center + Offset(half * 0.6f, half * 0.05f), stroke, StrokeCap.Round)
            drawLine(color, hip, center + Offset(-half * 0.45f, half), stroke, StrokeCap.Round)
            drawLine(color, hip, center + Offset(half * 0.45f, half), stroke, StrokeCap.Round)
        }
        ControlIcon.Flip -> {
            // Camera body with two turning arrows around the lens.
            val bodySize = Size(iconSize, iconSize * 0.7f)
            val bodyTopLeft = center - Offset(half, bodySize.height / 2)
            drawRoundRect(color, bodyTopLeft, bodySize, CornerRadius(stroke * 2), style = Stroke(stroke))
            val lensRadius = iconSize * 0.2f
            val lensBox = Size(lensRadius * 2, lensRadius * 2)
            val lensTopLeft = center - Offset(lensRadius, lensRadius)
            drawArc(color, 200f, 120f, false, lensTopLeft, lensBox, style = Stroke(stroke * 0.8f))
            drawArc(color, 20f, 120f, false, lensTopLeft, lensBox, style = Stroke(stroke * 0.8f))
            drawArrowHead(center + Offset(lensRadius * 0.5f, -lensRadius * 0.87f), Offset(1f, 0.5f), lensRadius * 0.7f, color, stroke * 0.8f)
            drawArrowHead(center + Offset(-lensRadius * 0.5f, lensRadius * 0.87f), Offset(-1f, -0.5f), lensRadius * 0.7f, color, stroke * 0.8f)
        }
    }
}

/** Two short lines forming an arrow head at [tip], pointing along [direction]. */
private fun DrawScope.drawArrowHead(tip: Offset, direction: Offset, length: Float, color: Color, strokeWidth: Float) {
    val unit = direction / direction.getDistance()
    val back = tip - unit * length
    val side = Offset(-unit.y, unit.x) * (length * 0.6f)
    drawLine(color, tip, back + side, strokeWidth, StrokeCap.Round)
    drawLine(color, tip, back - side, strokeWidth, StrokeCap.Round)
}
