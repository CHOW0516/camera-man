package com.cameraman.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ON_TARGET_DEGREES = 15f

/**
 * Scan mode screen: first asks the user to turn in a circle, then points to the best direction.
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
    val panelColor = if (isOnTarget) Color(0xCC1E7B34) else Color(0xCC000000)

    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp)
            .background(panelColor, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            heading == null -> {
                Text("Scan needs a motion sensor this phone does not have.", color = Color.White)
            }
            bestHeading == null -> {
                Text(
                    "Hold the phone upright and turn slowly in a full circle.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                LinearProgressIndicator(
                    progress = { (session.directionsSeen.toFloat() / DIRECTIONS_NEEDED).coerceAtMost(1f) },
                    color = Color.White
                )
            }
            isOnTarget -> {
                Text(
                    "Shoot this way!\nThe subject stands in front of you, facing the light behind you.",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                val turn = turnDegrees(heading, bestHeading)
                val arrow = if (turn > 0) "→" else "←"
                val side = if (turn > 0) "right" else "left"
                Text(arrow, color = Color.White, fontSize = 64.sp)
                Text(
                    "Turn $side about ${(abs(turn) / 5f).roundToInt() * 5}°",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }
        Button(onClick = onClose) {
            Text(if (bestHeading == null) "Cancel scan" else "Done")
        }
    }
}
