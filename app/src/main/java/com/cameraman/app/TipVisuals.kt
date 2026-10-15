package com.cameraman.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

val WarningRed = Color(0xFFE53935)
val GoodGreen = Color(0xFF43A047)
val LightYellow = Color(0xFFFFD54F)

private fun badgeColor(level: TipLevel) = when (level) {
    TipLevel.Warning -> WarningRed.copy(alpha = 0.85f)
    TipLevel.Good -> GoodGreen.copy(alpha = 0.85f)
    TipLevel.Info -> Color.Black.copy(alpha = 0.55f)
}

/** Round badge at the top of the screen showing the current tip as an icon. */
@Composable
fun TipBadge(kind: TipKind, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(72.dp)
            .background(badgeColor(kind.level), CircleShape)
    ) {
        drawTipIcon(kind, center, size.minDimension * 0.6f)
    }
}

/** Gives one short buzz each time the tip changes to "looks good". */
@Composable
fun BuzzWhenGood(kind: TipKind?) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(kind) {
        if (kind == TipKind.LooksGood) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** A green glow around the whole screen, shown when the shot is ready. */
fun DrawScope.drawSuccessFrame() {
    val width = 10.dp.toPx()
    drawRoundRect(
        color = GoodGreen,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(24.dp.toPx()),
        style = Stroke(width = width)
    )
}

/** Four arrows at the corners of [area], pointing out (come closer) or in (step back). */
fun DrawScope.drawCornerArrows(area: Rect, pointInward: Boolean, color: Color = Color.White) {
    val reach = 28.dp.toPx()
    val corners = listOf(
        area.topLeft to Offset(-1f, -1f),
        area.topRight to Offset(1f, -1f),
        area.bottomLeft to Offset(-1f, 1f),
        area.bottomRight to Offset(1f, 1f)
    )
    for ((corner, outward) in corners) {
        val outside = corner + outward * reach
        if (pointInward) drawArrow(outside, corner, color) else drawArrow(corner, outside, color)
    }
}

/** Straight arrow with a V-shaped head at [to]. */
fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, strokeWidth: Float = 4.dp.toPx()) {
    drawLine(color, from, to, strokeWidth, cap = StrokeCap.Round)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val headLength = strokeWidth * 4
    for (wing in listOf(-0.5f, 0.5f)) {
        val wingAngle = angle + PI.toFloat() + wing
        drawLine(color, to, to + Offset(cos(wingAngle) * headLength, sin(wingAngle) * headLength), strokeWidth, cap = StrokeCap.Round)
    }
}

/** A pulsing radius between 0.7x and 1.3x of [base], for things that should catch the eye. */
@Composable
fun pulsingRadius(base: Float): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulseScale"
    )
    return base * scale
}

/** Draws the icon for [kind], fitting in a square of [iconSize] around [center]. */
fun DrawScope.drawTipIcon(kind: TipKind, center: Offset, iconSize: Float, color: Color = Color.White) {
    val half = iconSize / 2
    val stroke = iconSize * 0.08f
    when (kind) {
        TipKind.TooDark -> {
            // Crescent moon.
            val moon = Path().apply { addOval(Rect(center, half * 0.8f)) }
            val bite = Path().apply { addOval(Rect(center + Offset(half * 0.4f, -half * 0.3f), half * 0.7f)) }
            val crescent = Path().apply { op(moon, bite, PathOperation.Difference) }
            drawPath(crescent, color)
        }
        TipKind.Backlit -> {
            // Sun behind a dark person.
            drawSun(center + Offset(half * 0.35f, -half * 0.35f), half * 0.35f, half * 0.6f, LightYellow, stroke)
            drawPerson(center + Offset(-half * 0.2f, half * 0.15f), iconSize * 0.7f, Color.Black, filled = true)
        }
        TipKind.HarshLight -> drawSun(center, half * 0.35f, half, LightYellow, stroke * 1.4f)
        TipKind.SideLight -> {
            // Face with one half in shadow and the light beside it.
            drawCircle(color, radius = half * 0.55f, center = center - Offset(half * 0.25f, 0f), style = Stroke(stroke))
            drawArc(
                color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                topLeft = center - Offset(half * 0.8f, half * 0.55f), size = Size(half * 1.1f, half * 1.1f)
            )
            drawSun(center + Offset(half * 0.7f, 0f), half * 0.15f, half * 0.3f, LightYellow, stroke * 0.7f)
        }
        TipKind.StepCloser, TipKind.StepBack -> {
            val inner = Rect(center, half * 0.35f)
            drawRect(color, inner.topLeft, inner.size, style = Stroke(stroke))
            val outer = Rect(center, half * 0.95f)
            val corners = listOf(
                inner.topLeft to outer.topLeft, inner.topRight to outer.topRight,
                inner.bottomLeft to outer.bottomLeft, inner.bottomRight to outer.bottomRight
            )
            for ((innerCorner, outerCorner) in corners) {
                // Closer = the subject grows (arrows out); back = it shrinks (arrows in).
                if (kind == TipKind.StepCloser) drawArrow(innerCorner, outerCorner, color, stroke)
                else drawArrow(outerCorner, innerCorner, color, stroke)
            }
        }
        TipKind.NeedHeadroom -> {
            drawPerson(center + Offset(0f, half * 0.3f), iconSize * 0.6f, color, filled = false, strokeWidth = stroke)
            drawArrow(center + Offset(0f, -half), center + Offset(0f, -half * 0.35f), color, stroke)
        }
        TipKind.LineUp -> {
            drawCircle(GoodGreen, radius = half * 0.3f, center = center + Offset(half * 0.5f, -half * 0.5f))
            drawCircle(color, radius = half * 0.25f, center = center + Offset(-half * 0.55f, half * 0.55f), style = Stroke(stroke))
            drawArrow(center + Offset(-half * 0.3f, half * 0.3f), center + Offset(half * 0.25f, -half * 0.25f), color, stroke)
        }
        TipKind.StandInBox -> {
            drawRoundRect(
                color, topLeft = center - Offset(half * 0.6f, half), size = Size(half * 1.2f, iconSize),
                cornerRadius = CornerRadius(stroke * 2), style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 2, stroke * 1.5f)))
            )
            drawPerson(center, iconSize * 0.75f, color, filled = false, strokeWidth = stroke)
        }
        TipKind.MoveSideways -> drawArrow(center - Offset(half, 0f), center + Offset(half, 0f), color, stroke * 1.5f)
        TipKind.FixLimb -> {
            drawPerson(center, iconSize, color, filled = false, strokeWidth = stroke)
            // Raised arm in red.
            val shoulder = center + Offset(half * 0.15f, -half * 0.35f)
            drawLine(WarningRed, shoulder, shoulder + Offset(half * 0.55f, -half * 0.45f), stroke * 1.6f, cap = StrokeCap.Round)
        }
        TipKind.LooksGood -> {
            val tick = Path().apply {
                moveTo(center.x - half * 0.6f, center.y)
                lineTo(center.x - half * 0.15f, center.y + half * 0.45f)
                lineTo(center.x + half * 0.65f, center.y - half * 0.5f)
            }
            drawPath(tick, color, style = Stroke(stroke * 1.5f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawSun(center: Offset, radius: Float, rayEnd: Float, color: Color, strokeWidth: Float) {
    drawCircle(color, radius = radius, center = center)
    for (ray in 0 until 8) {
        val angle = ray * PI.toFloat() / 4
        val direction = Offset(cos(angle), sin(angle))
        drawLine(color, center + direction * (radius * 1.35f), center + direction * rayEnd, strokeWidth, cap = StrokeCap.Round)
    }
}

/** Simple person icon: head and body, [height] tall, centered on [center]. */
private fun DrawScope.drawPerson(center: Offset, height: Float, color: Color, filled: Boolean, strokeWidth: Float = 0f) {
    val headRadius = height * 0.14f
    val headCenter = center - Offset(0f, height * 0.5f - headRadius)
    val bodyTop = headCenter.y + headRadius * 1.3f
    val bodySize = Size(height * 0.4f, center.y + height * 0.5f - bodyTop)
    val bodyTopLeft = Offset(center.x - bodySize.width / 2, bodyTop)
    if (filled) {
        drawCircle(color, headRadius, headCenter)
        drawRoundRect(color, bodyTopLeft, bodySize, CornerRadius(bodySize.width / 3))
    } else {
        drawCircle(color, headRadius, headCenter, style = Stroke(strokeWidth))
        drawRoundRect(color, bodyTopLeft, bodySize, CornerRadius(bodySize.width / 3), style = Stroke(strokeWidth))
    }
}

/** Small "?" button that opens a list of what each icon means. */
@Composable
fun LegendButton(modifier: Modifier = Modifier) {
    var isOpen by remember { mutableStateOf(false) }
    TextButton(
        onClick = { isOpen = true },
        modifier = modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
    ) {
        Text("?", color = Color.White, fontSize = 20.sp)
    }
    if (isOpen) {
        Dialog(onDismissRequest = { isOpen = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF202124), RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (kind in TipKind.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(
                            modifier = Modifier
                                .size(40.dp)
                                .background(badgeColor(kind.level), CircleShape)
                        ) {
                            drawTipIcon(kind, center, size.minDimension * 0.6f)
                        }
                        Text(kind.meaning, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                for (icon in ControlIcon.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black, CircleShape)
                        ) {
                            drawControlIcon(icon, center, size.minDimension * 0.55f, Color.White)
                        }
                        Text(icon.meaning, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                TextButton(onClick = { isOpen = false }, modifier = Modifier.align(Alignment.End)) {
                    Text("OK", color = Color.White)
                }
            }
        }
    }
}
