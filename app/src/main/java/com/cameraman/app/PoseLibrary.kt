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
 * [facingAway] poses are done with the back to the camera, so left and right swap on screen.
 * [goodFor] lists the scenes (places) where the pose is suggested first.
 */
data class TargetPose(
    val name: String,
    val boneAngles: Map<Bone, Float>,
    val goodFor: Set<Scene> = emptySet(),
    val facingAway: Boolean = false
)

/** The same pose seen in a mirror (for the selfie camera): left-right directions swap. */
fun TargetPose.mirrored(): TargetPose =
    copy(boneAngles = boneAngles.mapValues { (_, angle) -> (540f - angle) % 360f })

private val straightLegs = mapOf(
    Bone.LeftThigh to 88f, Bone.LeftShin to 90f,
    Bone.RightThigh to 92f, Bone.RightShin to 90f
)
private val walkingLegs = mapOf(
    Bone.LeftThigh to 70f, Bone.LeftShin to 95f,
    Bone.RightThigh to 100f, Bone.RightShin to 92f
)
private val crossedLegs = mapOf(
    Bone.LeftThigh to 100f, Bone.LeftShin to 98f,
    Bone.RightThigh to 92f, Bone.RightShin to 90f
)
/** Sitting on a step or chair, turned a little to the side: thighs forward, shins down. */
private val sittingLegs = mapOf(
    Bone.LeftThigh to 15f, Bone.LeftShin to 95f,
    Bone.RightThigh to 25f, Bone.RightShin to 100f
)
private val leftArmDown = mapOf(Bone.LeftUpperArm to 80f, Bone.LeftForearm to 85f)
private val rightArmDown = mapOf(Bone.RightUpperArm to 100f, Bone.RightForearm to 95f)
private val leftHandOnHip = mapOf(Bone.LeftUpperArm to 45f, Bone.LeftForearm to 135f)
private val rightHandOnHip = mapOf(Bone.RightUpperArm to 135f, Bone.RightForearm to 45f)
private val rightHandInPocket = mapOf(Bone.RightUpperArm to 110f, Bone.RightForearm to 60f)
private val rightHandOnKnee = mapOf(Bone.RightUpperArm to 80f, Bone.RightForearm to 30f)

/** Easy, popular influencer poses. The first ones whose [TargetPose.goodFor] matches the scene are suggested. */
val poseLibrary = listOf(
    TargetPose("Relaxed stand", leftArmDown + rightArmDown + straightLegs),
    TargetPose(
        "Hand on hip", leftHandOnHip + rightArmDown + straightLegs,
        goodFor = setOf(Scene.Street, Scene.DaytimePortrait, Scene.Architecture)
    ),
    TargetPose(
        "Hands on hips", leftHandOnHip + rightHandOnHip + straightLegs,
        goodFor = setOf(Scene.SkyPortrait, Scene.Landscape)
    ),
    TargetPose(
        "Walking step", leftArmDown + rightArmDown + walkingLegs,
        goodFor = setOf(Scene.Street, Scene.FilmStreet, Scene.Overcast, Scene.NightCity, Scene.Seaside)
    ),
    TargetPose(
        "Wave", mapOf(Bone.LeftUpperArm to 330f, Bone.LeftForearm to 275f) + rightArmDown + straightLegs,
        goodFor = setOf(Scene.SnowPortrait, Scene.Seaside)
    ),
    TargetPose(
        "Hand in hair", mapOf(Bone.LeftUpperArm to 300f, Bone.LeftForearm to 200f) + rightArmDown + straightLegs,
        goodFor = setOf(Scene.Seaside, Scene.Sunset, Scene.DaytimePortrait, Scene.SkyPortrait)
    ),
    TargetPose(
        "Arms up",
        mapOf(
            Bone.LeftUpperArm to 300f, Bone.LeftForearm to 290f,
            Bone.RightUpperArm to 240f, Bone.RightForearm to 250f
        ) + straightLegs,
        goodFor = setOf(Scene.SkyPortrait, Scene.Landscape, Scene.Seaside, Scene.SnowPortrait)
    ),
    TargetPose(
        "Crossed legs", leftHandOnHip + rightArmDown + crossedLegs,
        goodFor = setOf(Scene.Architecture, Scene.IndoorPortrait, Scene.NightPortrait)
    ),
    TargetPose(
        "Back view, arms open",
        mapOf(
            Bone.LeftUpperArm to 330f, Bone.LeftForearm to 325f,
            Bone.RightUpperArm to 210f, Bone.RightForearm to 215f
        ) + straightLegs,
        goodFor = setOf(Scene.Sunset, Scene.Afterglow, Scene.Seaside, Scene.Landscape, Scene.SkyPortrait),
        facingAway = true
    ),
    TargetPose(
        "Walk away, look back",
        mapOf(
            Bone.LeftUpperArm to 85f, Bone.LeftForearm to 90f,
            Bone.RightUpperArm to 95f, Bone.RightForearm to 90f
        ) + walkingLegs,
        goodFor = setOf(Scene.Street, Scene.FilmStreet, Scene.Sunset, Scene.Seaside, Scene.Architecture),
        facingAway = true
    ),
    TargetPose(
        "Sit on a step",
        mapOf(Bone.LeftUpperArm to 70f, Bone.LeftForearm to 20f) + rightHandOnKnee + sittingLegs,
        goodFor = setOf(Scene.Street, Scene.FilmStreet, Scene.Architecture, Scene.Overcast, Scene.IndoorPortrait)
    ),
    TargetPose(
        "Sit, chin on hand",
        mapOf(Bone.LeftUpperArm to 75f, Bone.LeftForearm to 265f) + rightHandOnKnee + sittingLegs,
        goodFor = setOf(Scene.Food, Scene.MoodyStillLife, Scene.IndoorPortrait)
    ),
    TargetPose(
        "Hold a cup",
        mapOf(
            Bone.LeftUpperArm to 95f, Bone.LeftForearm to 215f,
            Bone.RightUpperArm to 85f, Bone.RightForearm to 325f
        ) + straightLegs,
        goodFor = setOf(Scene.Food, Scene.MoodyStillLife, Scene.IndoorPortrait, Scene.FilmStreet)
    ),
    TargetPose(
        "Lean on a wall", leftArmDown + rightHandInPocket + crossedLegs,
        goodFor = setOf(Scene.Architecture, Scene.Street, Scene.NightCity, Scene.NightPortrait)
    ),
    TargetPose(
        "Hands in pockets",
        mapOf(Bone.LeftUpperArm to 70f, Bone.LeftForearm to 120f) + rightHandInPocket + straightLegs,
        goodFor = setOf(Scene.Street, Scene.FilmStreet, Scene.Overcast, Scene.NightPortrait, Scene.NightCity)
    ),
    TargetPose(
        "Both hands in hair",
        mapOf(
            Bone.LeftUpperArm to 300f, Bone.LeftForearm to 200f,
            Bone.RightUpperArm to 240f, Bone.RightForearm to 340f
        ) + straightLegs,
        goodFor = setOf(Scene.DaytimePortrait, Scene.SkyPortrait, Scene.Seaside, Scene.Flowers)
    ),
    TargetPose(
        "Smell the flower", mapOf(Bone.LeftUpperArm to 85f, Bone.LeftForearm to 260f) + rightArmDown + straightLegs,
        goodFor = setOf(Scene.Flowers)
    ),
    TargetPose(
        "Point at the view", mapOf(Bone.LeftUpperArm to 330f, Bone.LeftForearm to 330f) + rightArmDown + straightLegs,
        goodFor = setOf(Scene.Landscape, Scene.Architecture, Scene.Sunset, Scene.NightCity, Scene.Afterglow)
    ),
    TargetPose(
        "Hold your skirt or coat out",
        mapOf(
            Bone.LeftUpperArm to 55f, Bone.LeftForearm to 45f,
            Bone.RightUpperArm to 125f, Bone.RightForearm to 135f
        ) + straightLegs,
        goodFor = setOf(Scene.Flowers, Scene.DaytimePortrait, Scene.SkyPortrait, Scene.SnowPortrait)
    ),
    TargetPose(
        "Squat down",
        mapOf(
            Bone.LeftUpperArm to 70f, Bone.LeftForearm to 40f,
            Bone.RightUpperArm to 110f, Bone.RightForearm to 140f,
            Bone.LeftThigh to 30f, Bone.LeftShin to 100f,
            Bone.RightThigh to 150f, Bone.RightShin to 80f
        ),
        goodFor = setOf(Scene.Pets, Scene.Flowers)
    ),
    TargetPose(
        "Peace sign by your face", mapOf(Bone.LeftUpperArm to 95f, Bone.LeftForearm to 250f) + rightArmDown + straightLegs,
        goodFor = setOf(Scene.IndoorPortrait, Scene.DaytimePortrait, Scene.NightPortrait, Scene.Food)
    )
)

/** How many poses at the top of the list count as suggestions for the scene. */
const val SUGGESTED_POSE_COUNT = 3

/**
 * All poses, best first: those good for the [place] (what is around the person), then those good for the
 * [portrait] scene (light and indoor/outdoor), then the rest in library order.
 */
fun posesFor(place: Scene?, portrait: Scene?): List<TargetPose> = poseLibrary.sortedBy { pose ->
    when {
        place in pose.goodFor -> 0
        portrait in pose.goodFor -> 1
        else -> 2
    }
}

/** True when [pose] was picked for this place or scene, not just listed. */
fun isSuggested(pose: TargetPose, place: Scene?, portrait: Scene?): Boolean =
    place in pose.goodFor || portrait in pose.goodFor

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
    // Sitting and squatting poses are shorter: move the figure down so the feet stay at the bottom of the box.
    val lowestFoot = maxOf(joints.getValue(Joint.LeftAnkle).y, joints.getValue(Joint.RightAnkle).y)
    val drop = box.bottom - lowestFoot
    return joints.mapValues { (_, point) ->
        val x = if (mirrored) 2 * box.center.x - point.x else point.x
        Offset(x, point.y + drop)
    }
}
