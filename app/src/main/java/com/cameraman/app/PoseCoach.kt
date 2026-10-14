package com.cameraman.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

private const val TOO_DARK_BRIGHTNESS = 45f
private const val MIN_JOINTS_FOR_PERSON = 6
private const val ANGLE_FOR_ZERO_SCORE = 60f
const val GOOD_MATCH = 0.8f
private const val CENTER_TOLERANCE = 0.08f
private const val MIN_HEIGHT_RATIO = 0.8f
private const val MAX_HEIGHT_RATIO = 1.15f

/** How well a person matches a target pose: 0..1, and the bone furthest off. */
data class PoseMatch(val score: Float, val worstBone: Bone?)

/** Screen direction of a bone in degrees (0 = right, 90 = down), or null if a joint is missing. */
fun boneAngle(bone: Bone, joints: Map<Joint, Offset>): Float? {
    val start = joints[bone.start] ?: return null
    val end = joints[bone.end] ?: return null
    val degrees = Math.toDegrees(atan2(end.y - start.y, end.x - start.x).toDouble()).toFloat()
    return (degrees + 360f) % 360f
}

/** Smallest difference between two directions, 0..180 degrees. */
fun angleDifference(first: Float, second: Float): Float {
    val difference = abs(first - second) % 360f
    return if (difference > 180f) 360f - difference else difference
}

/**
 * Compares each visible bone's direction with the target. A bone scores 1 when it points the
 * same way and 0 when it is 60 degrees or more off; the pose score is the average.
 */
fun matchPose(joints: Map<Joint, Offset>, target: TargetPose): PoseMatch {
    var totalScore = 0f
    var boneCount = 0
    var worstBone: Bone? = null
    var worstDifference = -1f
    for (bone in Bone.entries) {
        val angle = boneAngle(bone, joints) ?: continue
        val difference = angleDifference(angle, target.boneAngles.getValue(bone))
        totalScore += max(0f, 1f - difference / ANGLE_FOR_ZERO_SCORE)
        boneCount++
        if (difference > worstDifference) {
            worstDifference = difference
            worstBone = bone
        }
    }
    if (boneCount == 0) return PoseMatch(0f, null)
    return PoseMatch(totalScore / boneCount, worstBone)
}

/**
 * Picks one tip for pose mode. [joints] are the person's joints in screen pixels,
 * [standBox] is where they should stand, [screenWidth] is the preview width in pixels.
 * Order: light, person visible, standing spot and distance, then the pose itself.
 */
fun choosePoseTip(
    frameBrightness: Float,
    joints: Map<Joint, Offset>?,
    standBox: Rect,
    screenWidth: Float,
    match: PoseMatch?
): Tip {
    if (frameBrightness < TOO_DARK_BRIGHTNESS) {
        return Tip("Too dark. Move to more light or face a window.", TipLevel.Warning)
    }
    if (joints == null || joints.size < MIN_JOINTS_FOR_PERSON || match == null) {
        return Tip("Stand in the box so the whole body is visible.", TipLevel.Info)
    }
    val leftAnkle = joints[Joint.LeftAnkle]
    val rightAnkle = joints[Joint.RightAnkle]
    if (leftAnkle == null && rightAnkle == null) {
        return Tip("Step back so the feet are in the photo.", TipLevel.Info)
    }

    val hips = listOfNotNull(joints[Joint.LeftHip], joints[Joint.RightHip])
    if (hips.isNotEmpty()) {
        val hipCenterX = hips.map { it.x }.average().toFloat()
        val offCenter = (standBox.center.x - hipCenterX) / screenWidth
        if (abs(offCenter) > CENTER_TOLERANCE) {
            val side = if (offCenter > 0) "right" else "left"
            return Tip("Move to the $side, into the box.", TipLevel.Info)
        }
    }

    val head = joints[Joint.Nose]
    val lowestFoot = listOfNotNull(leftAnkle, rightAnkle).maxOf { it.y }
    if (head != null) {
        // Nose-to-ankle covers about 90% of a person's height.
        val bodyHeight = (lowestFoot - head.y) / 0.9f
        val heightRatio = bodyHeight / standBox.height
        if (heightRatio < MIN_HEIGHT_RATIO) return Tip("Come a little closer to the camera.", TipLevel.Info)
        if (heightRatio > MAX_HEIGHT_RATIO) return Tip("Step back from the camera.", TipLevel.Info)
    }

    if (match.score < GOOD_MATCH && match.worstBone != null) {
        return Tip("Move your ${match.worstBone.bodyPart} to match the outline.", TipLevel.Info)
    }
    return Tip("Great pose! Take the shot.", TipLevel.Good)
}
