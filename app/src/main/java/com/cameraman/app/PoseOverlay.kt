package com.cameraman.app

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Where the person should stand, as fractions of the screen: on a thirds line, feet near the bottom.
private const val STAND_BOX_CENTER_X = 2f / 3
private const val STAND_BOX_TOP = 0.18f
private const val STAND_BOX_BOTTOM = 0.88f

/** How far a finger must slide on the pose card to change the pose. */
private val SWIPE_DISTANCE = 40.dp

/** Shows where to stand, the target pose outline, the person's skeleton, the match score and a tip. */
@Composable
fun PoseOverlay(
    reading: FrameReading?,
    isFrontCamera: Boolean,
    targetPose: TargetPose,
    poseNumber: Int,
    poseCount: Int,
    isSuggested: Boolean,
    onNextPose: () -> Unit,
    onPreviousPose: () -> Unit,
    zoom: ZoomInfo?,
    pitchDegrees: Float?,
    tooDarkForScene: Boolean?,
    onZoom: (Float) -> Unit,
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
        // The selfie preview is a mirror image, and a back-view pose swaps left and right too,
        // so in either case (but not both) the person copies a mirrored pose.
        val mirror = isFrontCamera != targetPose.facingAway
        val targetJoints = stickFigure(targetPose, standBox, mirrored = mirror)
        val poseToMatch = if (mirror) targetPose.mirrored() else targetPose

        val personJoints = reading?.bodyPoints?.mapValues { (_, imagePoint) ->
            val screenPoint = toScreenPoint(PointF(imagePoint.x, imagePoint.y), reading.imageAspectRatio, viewAspectRatio, isFrontCamera)
            Offset(screenPoint.x * screenWidth, screenPoint.y * screenHeight)
        }
        val match = personJoints?.let { matchPose(it, poseToMatch) }
        val newTip = reading?.let { choosePoseTip(it.frameBrightness, personJoints, standBox, screenWidth, match, zoom, pitchDegrees, tooDarkForScene) }
        val tipStabilizer = remember { TipStabilizer() }
        val tip = tipStabilizer.update(newTip, hasReading = reading != null)
        BuzzWhenGood(tip?.kind)
        val limbToFix = tip?.bone?.bodyPart

        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxColor = if (tip?.kind == TipKind.StandInBox) Color.White else Color.White.copy(alpha = 0.6f)
            drawRoundRect(
                color = boxColor,
                topLeft = standBox.topLeft,
                size = standBox.size,
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)))
            )
            drawSkeleton(targetJoints, Color.White.copy(alpha = 0.7f), strokeWidth = 6.dp.toPx(), headRadius = HEAD_RADIUS * standBox.height)
            if (limbToFix != null) {
                // The limb to fix glows red on the outline.
                for (bone in Bone.entries.filter { it.bodyPart == limbToFix }) {
                    val start = targetJoints[bone.start] ?: continue
                    val end = targetJoints[bone.end] ?: continue
                    drawLine(WarningRed, start, end, 9.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            if (personJoints != null) {
                val matchColor = if ((match?.score ?: 0f) >= GOOD_MATCH) GoodGreen else Color(0xFFFFC107)
                drawSkeleton(personJoints, matchColor, strokeWidth = 3.dp.toPx(), headRadius = null)
            }
            when (tip?.kind) {
                TipKind.MoveSideways -> {
                    // Big arrow on the far side of the box, pointing into it.
                    val y = standBox.center.y
                    val arrowLength = 64.dp.toPx()
                    if (tip?.side == Side.Right) {
                        drawArrow(Offset(standBox.left - arrowLength - 16.dp.toPx(), y), Offset(standBox.left - 16.dp.toPx(), y), Color.White, 8.dp.toPx())
                    } else {
                        drawArrow(Offset(standBox.right + arrowLength + 16.dp.toPx(), y), Offset(standBox.right + 16.dp.toPx(), y), Color.White, 8.dp.toPx())
                    }
                }
                TipKind.StepCloser -> drawCornerArrows(standBox, pointInward = false)
                TipKind.StepBack -> drawCornerArrows(standBox, pointInward = true)
                TipKind.LooksGood -> drawSuccessFrame()
                else -> tip?.let { drawCameraMoveHint(it, zoom) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (tip != null) TipBadge(kind = tip.kind, onClick = tip.zoomTo?.let { ratio -> { onZoom(ratio) } })
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScoreRing(score = match?.score)
            TextButton(onClick = onNextPose) {
                Text("›", color = Color.White, fontSize = 32.sp)
            }
        }
        PoseCard(
            name = targetPose.name,
            poseNumber = poseNumber,
            poseCount = poseCount,
            isSuggested = isSuggested,
            onNext = onNextPose,
            onPrevious = onPreviousPose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 210.dp)
        )
    }
}

/**
 * The pose's name with ✨ and "1/3" when it is suggested for this scene. Swipe left or right for the next or
 * previous pose.
 */
@Composable
private fun PoseCard(
    name: String,
    poseNumber: Int,
    poseCount: Int,
    isSuggested: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val label = if (isSuggested) "✨ $poseNumber/${minOf(poseCount, SUGGESTED_POSE_COUNT)}" else "$poseNumber/$poseCount"
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        if (dragTotal < -SWIPE_DISTANCE.toPx()) onNext()
                        if (dragTotal > SWIPE_DISTANCE.toPx()) onPrevious()
                    },
                    onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount }
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("‹", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
        Text(label, color = if (isSuggested) LightYellow else Color.White, fontSize = 14.sp)
        Text(name, color = Color.White, fontSize = 16.sp)
        Text("›", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
    }
}

/** A ring that fills up with the match score; green from 80%. Empty when nobody is found. */
@Composable
private fun ScoreRing(score: Float?) {
    Canvas(modifier = Modifier.size(56.dp)) {
        val stroke = 6.dp.toPx()
        val ringSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2, stroke / 2)
        drawArc(Color.Black.copy(alpha = 0.5f), 0f, 360f, useCenter = false, topLeft = topLeft, size = ringSize, style = Stroke(stroke))
        if (score != null) {
            val color = if (score >= GOOD_MATCH) GoodGreen else Color(0xFFFFC107)
            drawArc(color, -90f, 360f * score, useCenter = false, topLeft = topLeft, size = ringSize, style = Stroke(stroke, cap = StrokeCap.Round))
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
