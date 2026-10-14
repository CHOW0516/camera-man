package com.cameraman.app

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Where the person should stand, as fractions of the screen: on a thirds line, feet near the bottom.
private const val STAND_BOX_CENTER_X = 2f / 3
private const val STAND_BOX_TOP = 0.18f
private const val STAND_BOX_BOTTOM = 0.88f

/** Shows where to stand, the target pose outline, the person's skeleton, the match score and a tip. */
@Composable
fun PoseOverlay(
    reading: FrameReading?,
    isFrontCamera: Boolean,
    targetPose: TargetPose,
    onNextPose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val viewAspectRatio = screenWidth / screenHeight

        val boxHeight = (STAND_BOX_BOTTOM - STAND_BOX_TOP) * screenHeight
        val boxWidth = boxHeight * 0.45f
        val standBox = Rect(
            left = STAND_BOX_CENTER_X * screenWidth - boxWidth / 2,
            top = STAND_BOX_TOP * screenHeight,
            right = STAND_BOX_CENTER_X * screenWidth + boxWidth / 2,
            bottom = STAND_BOX_BOTTOM * screenHeight
        )
        val targetJoints = stickFigure(targetPose, standBox, mirrored = isFrontCamera)
        // The selfie preview is a mirror image, so the person copies a mirrored pose.
        val poseToMatch = if (isFrontCamera) targetPose.mirrored() else targetPose

        val personJoints = reading?.bodyPoints?.mapValues { (_, imagePoint) ->
            val screenPoint = toScreenPoint(PointF(imagePoint.x, imagePoint.y), reading.imageAspectRatio, viewAspectRatio, isFrontCamera)
            Offset(screenPoint.x * screenWidth, screenPoint.y * screenHeight)
        }
        val match = personJoints?.let { matchPose(it, poseToMatch) }
        val newTip = reading?.let { choosePoseTip(it.frameBrightness, personJoints, standBox, screenWidth, match) }
        val tipStabilizer = remember { TipStabilizer() }
        val tip = tipStabilizer.update(newTip)

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = standBox.topLeft,
                size = standBox.size,
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)))
            )
            drawSkeleton(targetJoints, Color.White.copy(alpha = 0.7f), strokeWidth = 6.dp.toPx(), headRadius = HEAD_RADIUS * standBox.height)
            if (personJoints != null) {
                val matchColor = if ((match?.score ?: 0f) >= GOOD_MATCH) Color.Green else Color(0xFFFFC107)
                drawSkeleton(personJoints, matchColor, strokeWidth = 3.dp.toPx(), headRadius = null)
            }
        }

        val bannerColor = when (tip?.level) {
            TipLevel.Warning -> Color(0xCCB3261E)
            TipLevel.Good -> Color(0xCC1E7B34)
            else -> Color(0x99000000)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
                .background(bannerColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val scoreText = match?.let { " · ${(it.score * 100).roundToInt()}% match" } ?: ""
            Text("${targetPose.name}$scoreText", color = Color.White, fontSize = 14.sp)
            if (tip != null) Text(tip.message, color = Color.White, fontSize = 16.sp)
            TextButton(onClick = onNextPose) {
                Text("Next pose", color = Color.White)
            }
        }
    }
}

/** Draws lines between joints, plus a head circle above the shoulders when [headRadius] is given. */
private fun DrawScope.drawSkeleton(joints: Map<Joint, Offset>, color: Color, strokeWidth: Float, headRadius: Float?) {
    val lines = Bone.entries.map { it.start to it.end } + listOf(
        Joint.LeftShoulder to Joint.RightShoulder,
        Joint.LeftHip to Joint.RightHip,
        Joint.LeftShoulder to Joint.LeftHip,
        Joint.RightShoulder to Joint.RightHip
    )
    for ((startJoint, endJoint) in lines) {
        val start = joints[startJoint] ?: continue
        val end = joints[endJoint] ?: continue
        drawLine(color, start, end, strokeWidth)
    }
    val nose = joints[Joint.Nose] ?: return
    if (headRadius != null) {
        drawCircle(color, radius = headRadius, center = nose, style = Stroke(width = strokeWidth))
    } else {
        drawCircle(color, radius = strokeWidth * 2, center = nose)
    }
}
