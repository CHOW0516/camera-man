package com.cameraman.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Rule-of-thirds grid: two vertical and two horizontal lines. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val lineColor = Color.White.copy(alpha = 0.5f)
        val strokeWidth = 1.dp.toPx()
        for (third in 1..2) {
            val x = size.width * third / 3
            val y = size.height * third / 3
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth)
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth)
        }
    }
}
