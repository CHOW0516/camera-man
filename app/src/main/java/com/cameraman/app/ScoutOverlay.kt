package com.cameraman.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Scores at or above these are green or yellow; below is red. */
private const val GOOD_SCORE = 70
private const val OKAY_SCORE = 45

/** Facing within this many degrees of a saved view counts as facing it. */
private const val ON_TARGET_DEGREES = 15f

/** Pointing up or down within this many degrees of a saved view counts as the same tilt. */
private const val ON_TARGET_TILT_DEGREES = 8f

fun scoreColor(score: Int): Color = when {
    score >= GOOD_SCORE -> GoodGreen
    score >= OKAY_SCORE -> LightYellow
    else -> WarningRed
}

/** Ring that fills up with the AI view score (0..100), colored green, yellow or red, with the number inside. */
@Composable
fun ScoreRing(score: Int?, modifier: Modifier = Modifier, ringSize: Dp = 64.dp) {
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val stroke = 6.dp.toPx()
            val radius = size.minDimension / 2 - stroke
            val topLeft = Offset(center.x - radius, center.y - radius)
            val ringArea = Size(radius * 2, radius * 2)
            drawCircle(Color.Black.copy(alpha = 0.45f), radius + stroke, center)
            drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = topLeft, size = ringArea, style = Stroke(stroke))
            if (score != null) {
                drawArc(scoreColor(score), -90f, 360f * score / 100f, useCenter = false, topLeft = topLeft, size = ringArea, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(score?.toString() ?: "…", color = Color.White, fontSize = (ringSize.value * 0.32f).sp)
    }
}

/**
 * Scout mode: the live score of the current view, the best views kept so far (tap one to find it again),
 * and an optional Gemini button to pick between them.
 * [currentEmbedding] is the latest view's embedding, to tell how close the user is to a selected view.
 */
@Composable
fun ScoutOverlay(
    session: ScoutSession,
    liveScore: Int?,
    selected: ScoutView?,
    heading: Float?,
    pitchDegrees: Float?,
    currentEmbedding: FloatArray?,
    aiPick: ScoutView?,
    askingAi: Boolean,
    canAskAi: Boolean,
    onAskAi: () -> Unit,
    onSelect: (ScoutView?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 72.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreRing(liveScore)
            if (canAskAi && selected == null) {
                val sparkleScale = if (askingAi) pulsingRadius(base = 1f) else 1f
                Canvas(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable(enabled = !askingAi) { onAskAi() }
                ) {
                    drawSparkle(center, size.minDimension * 0.3f * sparkleScale, LightYellow)
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Text("✕", color = Color.White, fontSize = 20.sp)
            }
        }

        if (selected == null) {
            if (session.bestViews.isEmpty()) {
                WalkHint()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (view in session.bestViews) {
                        ViewThumbnail(view.bitmap, view.score, isAiPick = view === aiPick, width = 84.dp, onClick = { onSelect(view) })
                    }
                }
            }
        } else {
            FindAgain(selected, heading, pitchDegrees, currentEmbedding, isAiPick = selected === aiPick, onBack = { onSelect(null) })
        }
    }
}

/** Walking person with an arrow: walk around, the app keeps the best views. */
@Composable
private fun WalkHint() {
    Canvas(modifier = Modifier.size(width = 120.dp, height = 48.dp)) {
        val left = Offset(size.width * 0.2f, center.y)
        drawCircle(Color.White, size.height * 0.12f, Offset(left.x, size.height * 0.18f))
        drawLine(Color.White, Offset(left.x, size.height * 0.3f), Offset(left.x, size.height * 0.65f), 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(left.x, size.height * 0.65f), Offset(left.x - 8.dp.toPx(), size.height * 0.95f), 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(left.x, size.height * 0.65f), Offset(left.x + 8.dp.toPx(), size.height * 0.95f), 4.dp.toPx(), cap = StrokeCap.Round)
        drawArrow(Offset(size.width * 0.4f, center.y), Offset(size.width * 0.9f, center.y), Color.White, 4.dp.toPx())
    }
}

@Composable
private fun ViewThumbnail(bitmap: Bitmap, score: Int, isAiPick: Boolean, width: Dp, onClick: () -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .border(3.dp, scoreColor(score), RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.width(width))
        Text(
            score.toString(),
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(scoreColor(score).copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp)
        )
        if (isAiPick) {
            Canvas(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)) {
                drawSparkle(center, size.minDimension / 2, LightYellow)
            }
        }
    }
}

/**
 * Leads back to a saved view: its photo, an arrow to turn or tilt toward it, and a match ring that turns green
 * once the camera sees the same view again. Tap the photo to go back to the list.
 */
@Composable
private fun FindAgain(
    view: ScoutView,
    heading: Float?,
    pitchDegrees: Float?,
    currentEmbedding: FloatArray?,
    isAiPick: Boolean,
    onBack: () -> Unit
) {
    val match = currentEmbedding?.let { similarity(it, view.embedding) }
    val isMatch = match != null && match >= SAME_VIEW_SIMILARITY
    val turn = if (heading != null && view.headingDegrees != null) turnDegrees(heading, view.headingDegrees) else null
    // Pitch: positive = camera points down. A target with a smaller pitch is higher up.
    val tilt = if (pitchDegrees != null && view.pitchDegrees != null) view.pitchDegrees - pitchDegrees else null
    val hint = when {
        isMatch -> TipKind.LooksGood
        turn != null && abs(turn) > ON_TARGET_DEGREES -> null
        tilt != null && tilt < -ON_TARGET_TILT_DEGREES -> TipKind.TiltUp
        tilt != null && tilt > ON_TARGET_TILT_DEGREES -> TipKind.TiltDown
        else -> null
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ViewThumbnail(view.bitmap, view.score, isAiPick, width = 140.dp, onClick = onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val radius = size.minDimension / 2
                when {
                    hint != null -> drawTipIcon(hint, center, radius * 1.4f, if (isMatch) GoodGreen else Color.White)
                    turn != null && abs(turn) > ON_TARGET_DEGREES -> {
                        val direction = if (turn > 0) 1f else -1f
                        drawArrow(center - Offset(direction * radius * 0.8f, 0f), center + Offset(direction * radius * 0.8f, 0f), Color.White, 8.dp.toPx())
                    }
                    // Facing the right way but the view does not match yet: walk back to where it was.
                    else -> drawArrow(center + Offset(0f, radius * 0.8f), center - Offset(0f, radius * 0.8f), Color.White, 8.dp.toPx())
                }
            }
            // How much the camera view looks like the saved one, as a ring.
            ScoreRing(match?.let { (it.coerceIn(0f, 1f) * 100).toInt() }, ringSize = 48.dp)
        }
    }
}

/** Four-pointed star: Gemini's pick, and the button to ask Gemini. */
fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
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
