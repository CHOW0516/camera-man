package com.cameraman.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.cos
import kotlin.math.sin

/** Body joints used for pose matching. "Left" and "right" are the person's own sides. */
enum class Joint(val mlKitType: Int) {
    Nose(PoseLandmark.NOSE),
    LeftShoulder(PoseLandmark.LEFT_SHOULDER),
    RightShoulder(PoseLandmark.RIGHT_SHOULDER),
    LeftElbow(PoseLandmark.LEFT_ELBOW),
    RightElbow(PoseLandmark.RIGHT_ELBOW),
    LeftWrist(PoseLandmark.LEFT_WRIST),
    RightWrist(PoseLandmark.RIGHT_WRIST),
    LeftHip(PoseLandmark.LEFT_HIP),
    RightHip(PoseLandmark.RIGHT_HIP),
    LeftKnee(PoseLandmark.LEFT_KNEE),
    RightKnee(PoseLandmark.RIGHT_KNEE),
    LeftAnkle(PoseLandmark.LEFT_ANKLE),
    RightAnkle(PoseLandmark.RIGHT_ANKLE)
}

/** A body segment from [start] to [end] joint; bones with the same [bodyPart] light up together. */
enum class Bone(val start: Joint, val end: Joint, val bodyPart: String) {
    LeftUpperArm(Joint.LeftShoulder, Joint.LeftElbow, "left arm"),
    LeftForearm(Joint.LeftElbow, Joint.LeftWrist, "left arm"),
    RightUpperArm(Joint.RightShoulder, Joint.RightElbow, "right arm"),
    RightForearm(Joint.RightElbow, Joint.RightWrist, "right arm"),
    LeftThigh(Joint.LeftHip, Joint.LeftKnee, "left leg"),
    LeftShin(Joint.LeftKnee, Joint.LeftAnkle, "left leg"),
    RightThigh(Joint.RightHip, Joint.RightKnee, "right leg"),
    RightShin(Joint.RightKnee, Joint.RightAnkle, "right leg")
}

/**
 * A pose to copy, as the screen direction of each bone in degrees:
 * 0 = pointing right on screen, 90 = down, 180 = left, 270 = up.
 * Written for a person facing the camera, so their left side is on the right of the screen.
 */
data class TargetPose(val name: String, val boneAngles: Map<Bone, Float>)

/** The same pose seen in a mirror (for the selfie camera): left-right directions swap. */
fun TargetPose.mirrored(): TargetPose =
    copy(boneAngles = boneAngles.mapValues { (_, angle) -> (540f - angle) % 360f })

private val straightLegs = mapOf(
    Bone.LeftThigh to 88f, Bone.LeftShin to 90f,
    Bone.RightThigh to 92f, Bone.RightShin to 90f
)
private val leftArmDown = mapOf(Bone.LeftUpperArm to 80f, Bone.LeftForearm to 85f)
private val rightArmDown = mapOf(Bone.RightUpperArm to 100f, Bone.RightForearm to 95f)
private val leftHandOnHip = mapOf(Bone.LeftUpperArm to 45f, Bone.LeftForearm to 135f)
private val rightHandOnHip = mapOf(Bone.RightUpperArm to 135f, Bone.RightForearm to 45f)

/** Starting set of easy, popular poses. Add more by listing bone angles. */
val poseLibrary = listOf(
    TargetPose("Relaxed stand", leftArmDown + rightArmDown + straightLegs),
    TargetPose("Hand on hip", leftHandOnHip + rightArmDown + straightLegs),
    TargetPose("Hands on hips", leftHandOnHip + rightHandOnHip + straightLegs),
    TargetPose(
        "Walking step",
        leftArmDown + rightArmDown + mapOf(
            Bone.LeftThigh to 70f, Bone.LeftShin to 95f,
            Bone.RightThigh to 100f, Bone.RightShin to 92f
        )
    ),
    TargetPose(
        "Wave",
        mapOf(Bone.LeftUpperArm to 330f, Bone.LeftForearm to 275f) + rightArmDown + straightLegs
    ),
    TargetPose(
        "Hand in hair",
        mapOf(Bone.LeftUpperArm to 300f, Bone.LeftForearm to 200f) + rightArmDown + straightLegs
    ),
    TargetPose(
        "Arms up",
        mapOf(
            Bone.LeftUpperArm to 300f, Bone.LeftForearm to 290f,
            Bone.RightUpperArm to 240f, Bone.RightForearm to 250f
        ) + straightLegs
    ),
    TargetPose(
        "Crossed legs",
        leftHandOnHip + rightArmDown + mapOf(
            Bone.LeftThigh to 100f, Bone.LeftShin to 98f,
            Bone.RightThigh to 92f, Bone.RightShin to 90f
        )
    )
)

// Body proportions as fractions of the person's full height.
private const val TORSO = 0.30f
private const val SHOULDER_HALF_WIDTH = 0.10f
private const val HIP_HALF_WIDTH = 0.06f
private const val UPPER_ARM = 0.15f
private const val FOREARM = 0.14f
private const val THIGH = 0.23f
private const val SHIN = 0.23f
const val HEAD_RADIUS = 0.06f

/**
 * Joint positions (in pixels) of a stick figure doing [pose], standing inside [box].
 * When [mirrored], the figure is flipped left-right, as the selfie camera shows it.
 */
fun stickFigure(pose: TargetPose, box: Rect, mirrored: Boolean): Map<Joint, Offset> {
    val height = box.height
    val hipCenter = Offset(box.center.x, box.bottom - (THIGH + SHIN) * height)
    val shoulderCenter = hipCenter - Offset(0f, TORSO * height)

    val joints = mutableMapOf(
        Joint.LeftShoulder to shoulderCenter + Offset(SHOULDER_HALF_WIDTH * height, 0f),
        Joint.RightShoulder to shoulderCenter - Offset(SHOULDER_HALF_WIDTH * height, 0f),
        Joint.LeftHip to hipCenter + Offset(HIP_HALF_WIDTH * height, 0f),
        Joint.RightHip to hipCenter - Offset(HIP_HALF_WIDTH * height, 0f),
        Joint.Nose to shoulderCenter - Offset(0f, (HEAD_RADIUS + 0.04f) * height)
    )
    val boneLengths = mapOf(
        Bone.LeftUpperArm to UPPER_ARM, Bone.LeftForearm to FOREARM,
        Bone.RightUpperArm to UPPER_ARM, Bone.RightForearm to FOREARM,
        Bone.LeftThigh to THIGH, Bone.LeftShin to SHIN,
        Bone.RightThigh to THIGH, Bone.RightShin to SHIN
    )
    // Bones are listed parent first, so each start joint is placed before its end joint.
    for (bone in Bone.entries) {
        val start = joints.getValue(bone.start)
        val radians = Math.toRadians(pose.boneAngles.getValue(bone).toDouble())
        val length = boneLengths.getValue(bone) * height
        joints[bone.end] = start + Offset((cos(radians) * length).toFloat(), (sin(radians) * length).toFloat())
    }
    if (!mirrored) return joints
    return joints.mapValues { (_, point) -> Offset(2 * box.center.x - point.x, point.y) }
}
