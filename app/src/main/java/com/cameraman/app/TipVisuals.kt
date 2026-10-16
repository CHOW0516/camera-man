package com.cameraman.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
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

/** Round badge at the top of the screen showing the current tip as an icon. Tappable when [onClick] is set. */
@Composable
fun TipBadge(kind: TipKind, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Canvas(
        modifier = modifier
            .size(72.dp)
            .background(badgeColor(kind.level), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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

/**
 * Screen-wide hints for moving the camera: the area a zoom-in will keep (dashed box),
 * or big curved arrows at the screen edges for tilting.
 */
fun DrawScope.drawCameraMoveHint(tip: Tip, zoom: ZoomInfo?) {
    when (tip.kind) {
        TipKind.ZoomIn -> {
            val zoomTo = tip.zoomTo ?: return
            val currentRatio = zoom?.ratio ?: return
            val keptSize = size / (zoomTo / currentRatio)
            drawRect(
                Color.White,
                topLeft = center - Offset(keptSize.width / 2, keptSize.height / 2),
                size = keptSize,
                style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f)))
            )
        }
        TipKind.TiltUp, TipKind.TiltDown -> {
            val arrowLength = 80.dp.toPx()
            val margin = 28.dp.toPx()
            val towardTop = if (tip.kind == TipKind.TiltUp) -1f else 1f
            for (x in listOf(margin, size.width - margin)) {
                val start = Offset(x, center.y - towardTop * arrowLength / 2)
                drawArrow(start, start + Offset(0f, towardTop * arrowLength), LightYellow, 6.dp.toPx())
            }
        }
        else -> Unit
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
        TipKind.ZoomIn, TipKind.ZoomOut -> {
            // Magnifier with + or -.
            val lensCenter = center - Offset(half * 0.15f, half * 0.15f)
            val lensRadius = half * 0.55f
            drawCircle(color, lensRadius, lensCenter, style = Stroke(stroke))
            drawLine(color, lensCenter + Offset(lensRadius, lensRadius) * 0.7f, center + Offset(half * 0.85f, half * 0.85f), stroke * 1.6f, cap = StrokeCap.Round)
            val bar = lensRadius * 0.5f
            drawLine(color, lensCenter - Offset(bar, 0f), lensCenter + Offset(bar, 0f), stroke, cap = StrokeCap.Round)
            if (kind == TipKind.ZoomIn) {
                drawLine(color, lensCenter - Offset(0f, bar), lensCenter + Offset(0f, bar), stroke, cap = StrokeCap.Round)
            }
        }
        TipKind.TiltUp, TipKind.TiltDown -> {
            // Phone with a curved arrow beside it: turn the camera up or down.
            drawPhone(center - Offset(half * 0.35f, 0f), iconSize * 0.75f, color, stroke)
            val arcBox = Rect(center + Offset(half * 0.1f, 0f), half * 0.6f)
            drawArc(color, -80f, 160f, useCenter = false, topLeft = arcBox.topLeft, size = arcBox.size, style = Stroke(stroke, cap = StrokeCap.Round))
            val arcTop = Offset(arcBox.center.x + half * 0.1f, arcBox.top)
            val arcBottom = Offset(arcBox.center.x + half * 0.1f, arcBox.bottom)
            if (kind == TipKind.TiltUp) {
                drawArrow(arcTop + Offset(half * 0.2f, half * 0.05f), arcTop, color, stroke)
            } else {
                drawArrow(arcBottom + Offset(half * 0.2f, -half * 0.05f), arcBottom, color, stroke)
            }
        }
        TipKind.RaisePhone, TipKind.LowerPhone -> {
            // Phone with a straight arrow beside it: move the whole phone up or down.
            drawPhone(center - Offset(half * 0.35f, 0f), iconSize * 0.75f, color, stroke)
            val arrowX = center.x + half * 0.5f
            val top = Offset(arrowX, center.y - half * 0.8f)
            val bottom = Offset(arrowX, center.y + half * 0.8f)
            if (kind == TipKind.RaisePhone) drawArrow(bottom, top, color, stroke) else drawArrow(top, bottom, color, stroke)
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

/** Phone outline, [height] tall, centered on [center]. */
private fun DrawScope.drawPhone(center: Offset, height: Float, color: Color, strokeWidth: Float) {
    val phoneSize = Size(height * 0.5f, height)
    drawRoundRect(color, center - Offset(phoneSize.width / 2, height / 2), phoneSize, CornerRadius(strokeWidth * 2), style = Stroke(strokeWidth))
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
                GeminiKeyField()
                TextButton(onClick = { isOpen = false }, modifier = Modifier.align(Alignment.End)) {
                    Text("OK", color = Color.White)
                }
            }
        }
    }
}

/** Lets the user paste a free Gemini API key; scan mode then asks Gemini to pick between the best views. */
@Composable
private fun GeminiKeyField() {
    val context = LocalContext.current
    var key by remember { mutableStateOf(loadGeminiKey(context) ?: "") }
    Text(
        "Optional: paste a free Gemini key (aistudio.google.com) and scan will also ask Gemini to pick the best view. " +
            "Scan photos are then sent to Google.",
        color = Color.White,
        fontSize = 13.sp
    )
    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it
            saveGeminiKey(context, it)
        },
        singleLine = true,
        placeholder = { Text("Gemini API key") },
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
    )
}
