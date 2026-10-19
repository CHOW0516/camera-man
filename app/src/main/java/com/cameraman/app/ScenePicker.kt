package com.cameraman.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Top-right chip: the scene in use, with "AUTO" when the app picked it. Below it, what the light meter reads. */
@Composable
fun SceneChip(scene: Scene?, isAuto: Boolean, meter: MeterReading?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .border(2.dp, if (scene == null) Color.White.copy(alpha = 0.4f) else LightYellow, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(scene?.emoji ?: "⭕", fontSize = 24.sp)
        }
        if (isAuto) Text("AUTO", color = LightYellow, fontSize = 10.sp)
        if (meter != null) {
            Text(
                "ISO ${meter.iso}\n${formatShutter(meter.exposureSeconds)}\nEV ${String.format(Locale.US, "%.1f", meter.ev100)}",
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 4.dp)
            )
        }
    }
}

/** Row of scene chips: auto, off (plain camera), then every recipe. The chosen one has a yellow ring. */
@Composable
fun ScenePicker(isAuto: Boolean, picked: Scene?, onAuto: () -> Unit, onPick: (Scene?) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SceneOption("✨", selected = isAuto, onClick = onAuto)
        SceneOption("⭕", selected = !isAuto && picked == null, onClick = { onPick(null) })
        for (scene in Scene.entries) {
            SceneOption(scene.emoji, selected = !isAuto && picked == scene, onClick = { onPick(scene) })
        }
    }
}

@Composable
private fun SceneOption(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) LightYellow else Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 20.sp)
    }
}
