package com.cameraman.app

import android.hardware.camera2.CaptureRequest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/** Slowest shutter that is still sharp handheld, used when a recipe leaves the shutter on auto. */
const val HANDHELD_SHUTTER_SECONDS = 1f / 60

/** A spot can need up to this many times the recipe's highest ISO before it counts as too dark (one stop of slack). */
private const val ISO_SLACK = 2f

/**
 * Camera recipes from the Xiaohongshu screenshots (see notes/smart-camera-looks.md).
 * [maxIso] is the highest ISO the recipe uses; [slowestShutterSeconds] is the recipe's shutter, used as the
 * slowest allowed (phones can't close the aperture, so in bright light the camera picks a faster one);
 * [kelvin] is the white balance, where a higher number gives a warmer photo; [zoom] is the suggested zoom.
 */
enum class Scene(
    val emoji: String,
    val title: String,
    val maxIso: Int,
    val slowestShutterSeconds: Float?,
    val kelvin: Int?,
    val evCompensation: Float,
    val zoom: Float? = null,
    val hasPerson: Boolean = false,
    /** Lines must stay straight (buildings), so the camera must be held level even without a face. */
    val keepLevel: Boolean = false,
    val look: PhotoLook? = null,
    /** Shooting advice from the screenshots, shown in the "?" legend. */
    val tip: String? = null
) {
    DaytimePortrait("🧍", "Daytime portrait", 100, 1 / 125f, 5500, -0.3f, hasPerson = true, look = Looks.BrightAiry),
    IndoorPortrait("🛋️", "Indoor portrait", 400, 1 / 125f, 4600, 0f, hasPerson = true, look = Looks.BrightAiry),
    SkyPortrait("🌤️", "Blue sky portrait", 100, 1 / 500f, 5500, -0.3f, hasPerson = true, look = Looks.CleanMinimal),
    // Snow fools the meter into a grey photo; +0.7 is the usual fix (not from the screenshots).
    SnowPortrait("❄️", "Snow portrait", 100, 1 / 500f, 5500, 0.7f, hasPerson = true, look = Looks.CleanMinimal),
    NightPortrait("🌃", "Night portrait", 500, 1 / 30f, 3850, 0f, hasPerson = true, look = Looks.DarkMoody),
    Street("🚶", "Street", 400, 1 / 125f, 5300, 0.3f, look = Looks.VividScene),
    Overcast("☁️", "Overcast street", 400, 1 / 200f, 5200, 0f, look = Looks.BrightAiry),
    FilmStreet("🎞️", "Fuji film street", 100, null, null, -0.7f, zoom = 3f, look = Looks.FujiFilm),
    Architecture("🏛️", "Architecture", 200, 1 / 200f, null, -0.3f, zoom = 3f, keepLevel = true, look = Looks.Architecture),
    Landscape("🏞️", "Landscape", 100, 1 / 200f, 5400, -0.3f, zoom = 1f, keepLevel = true, look = Looks.VividScene),
    Seaside("🌊", "Seaside", 100, 1 / 1000f, 5200, 0f, keepLevel = true, look = Looks.Sea, tip = "Put the waves in the foreground"),
    Sunset(
        "🌅", "Sunset", 100, 1 / 800f, 6200, -1f, zoom = 2f, keepLevel = true, look = Looks.Sunset,
        tip = "Meter on the sky beside the sun so the glow doesn't blow out"
    ),
    Afterglow(
        "🌇", "Afterglow (after sunset)", 640, 1 / 30f, 4600, 0f, keepLevel = true, look = Looks.Afterglow,
        tip = "Shoot when the cloud color is strongest"
    ),
    SunRays("✨", "Sun rays", 100, 1 / 250f, 5200, -0.3f, look = Looks.SunRays),
    Vintage("📻", "Vintage", 200, null, 7000, 0f, look = Looks.Vintage),
    // Sources disagree on food EV (Xiaomi −0.3, OPPO +0.3 to +0.7); bright food usually looks better.
    Food(
        "🍜", "Food and dessert", 200, 1 / 100f, 5500, 0.3f, zoom = 2f, look = Looks.BrightAiry,
        tip = "Shoot by a window and let the main dish fill most of the frame"
    ),
    Flowers("🌸", "Flowers", 250, 1 / 400f, 5200, 0f, zoom = 2f, look = Looks.VividScene),
    Product("📦", "Still life / product", 100, 1 / 125f, 5500, 0f, look = Looks.CleanMinimal),
    MoodyStillLife(
        "☕", "Moody drinks / still life", 400, null, null, -0.7f, zoom = 2.5f, look = Looks.MoodyStillLife,
        tip = "Plain dark background, zoom 2–3x, lower exposure; triangle, center or diagonal layout"
    ),
    Pets("🐶", "Pets and kids", 600, 1 / 400f, null, -0.3f, zoom = 3f, look = Looks.Pet),
    NightCity("🌆", "Night city", 400, 1 / 15f, 5000, -1f, zoom = 3f, keepLevel = true, look = Looks.NightCity),
    Moon("🌙", "Moon", 100, 1 / 250f, 4800, -2f, zoom = 10f, look = Looks.Moon);

    /** Settings line for the "?" legend, plus the shooting tip when there is one. */
    val summary: String
        get() {
            val shutter = slowestShutterSeconds?.let { formatShutter(it) } ?: "auto"
            val whiteBalance = kelvin?.let { "${it}K" } ?: "auto WB"
            val settings = "$title: ISO ≤ $maxIso, $shutter, $whiteBalance, EV ${formatEv(evCompensation)}"
            return if (tip == null) settings else "$settings\n$tip"
        }

    /**
     * Camera2 white balance preset closest to [kelvin], or null to keep auto. Neutral daylight (4600–6000 K) stays on
     * auto, which is already accurate there; presets are used for clearly warm or cool recipes.
     */
    fun whiteBalanceMode(): Int? {
        val target = kelvin ?: return null
        if (target in 4600..6000) return null
        val presets = listOf(
            2850 to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            4200 to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            5500 to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            6500 to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            7500 to CaptureRequest.CONTROL_AWB_MODE_SHADE
        )
        return presets.minBy { abs(it.first - target) }.second
    }

    /** Shutter used to judge light and to set manual exposure. */
    val shutterSeconds: Float get() = slowestShutterSeconds ?: HANDHELD_SHUTTER_SECONDS

    /** ISO this spot needs at the recipe's shutter, from what the camera measures now. */
    fun isoNeeded(meter: MeterReading): Float = meter.iso * meter.exposureSeconds / shutterSeconds

    /** True when the spot is too dark to shoot this recipe without much more noise than it allows. */
    fun isTooDark(meter: MeterReading): Boolean = isoNeeded(meter) > maxIso * ISO_SLACK
}

fun formatShutter(seconds: Float): String =
    if (seconds >= 1f) "${seconds.toInt()}s" else "1/${(1f / seconds).toInt()}"

fun formatEv(ev: Float): String = when {
    ev > 0f -> String.format(Locale.US, "+%.1f", ev)
    ev < 0f -> String.format(Locale.US, "−%.1f", -ev)
    else -> "0"
}

/** Number of label runs in a row a new scene must win before auto mode switches to it (stops flicker). */
private const val RUNS_BEFORE_SCENE_CHANGE = 3

/** Light level (EV at ISO 100) below which a scene counts as night, and below which it counts as indoors. */
private const val NIGHT_EV = 5f
private const val INDOOR_EV = 9f

/** A sunset scene darker than this (EV at ISO 100) is the afterglow once the sun has gone down. */
private const val AFTERGLOW_EV = 9f

/** Image labels (from ML Kit's built-in list) that point to each scene, checked in this order. */
private val labelScenes = listOf(
    Scene.Moon to setOf("Moon"),
    Scene.Sunset to setOf("Sunset", "Dusk"),
    Scene.Food to setOf("Food", "Cake", "Dessert", "Cookie", "Pizza", "Bread", "Fast food", "Cuisine", "Dish", "Sushi", "Noodle", "Coffee", "Tableware"),
    Scene.Flowers to setOf("Flower", "Petal", "Rose"),
    Scene.Pets to setOf("Dog", "Cat", "Pet", "Bird"),
    Scene.Seaside to setOf("Beach", "Sea", "Lake", "River", "Waterfall", "Surfing"),
    Scene.Architecture to setOf("Building", "Skyscraper", "Tower", "Church", "Temple", "Castle", "Bridge", "Monument", "Stadium"),
    Scene.Landscape to setOf("Mountain", "Sky", "Cloud", "Field", "Grass", "Forest", "Garden", "Plant", "Tree", "Desert"),
    Scene.Product to setOf("Bag", "Shoe", "Watch", "Glasses", "Toy", "Jewellery", "Musical instrument")
)

/** Guesses the scene from image labels, whether there is a person, and how much light there is. */
fun guessScene(labels: List<String>, hasPerson: Boolean, ev100: Float?): Scene {
    val labelSet = labels.toSet()
    fun has(vararg names: String) = names.any { it in labelSet }
    val isNight = ev100 != null && ev100 < NIGHT_EV
    val isIndoor = (ev100 != null && ev100 < INDOOR_EV) || has("Room", "Interior design", "Couch", "Bed", "Shelf", "Curtain")
    if (has("Dog", "Cat", "Pet")) return Scene.Pets
    if (hasPerson) {
        return when {
            isNight -> Scene.NightPortrait
            has("Snow") -> Scene.SnowPortrait
            isIndoor -> Scene.IndoorPortrait
            has("Sunset", "Dusk") -> Scene.Sunset
            has("Sky", "Cloud") -> Scene.SkyPortrait
            else -> Scene.DaytimePortrait
        }
    }
    val fromLabels = labelScenes.firstOrNull { (_, names) -> names.any { it in labelSet } }?.first
    if (isNight && fromLabels != Scene.Moon) return Scene.NightCity
    if (fromLabels == Scene.Sunset && ev100 != null && ev100 < AFTERGLOW_EV) return Scene.Afterglow
    return fromLabels ?: Scene.Street
}

/** Keeps the auto scene steady: a new guess must win a few label runs in a row. */
class SceneDetector {
    var scene: Scene = Scene.DaytimePortrait
        private set
    private var candidate: Scene? = null
    private var candidateCount = 0

    fun update(guess: Scene): Scene {
        if (guess == scene) {
            candidateCount = 0
        } else if (guess == candidate) {
            candidateCount++
            if (candidateCount >= RUNS_BEFORE_SCENE_CHANGE) {
                scene = guess
                candidateCount = 0
            }
        } else {
            candidate = guess
            candidateCount = 1
        }
        return scene
    }
}

/** Log base 2. */
fun log2(value: Float): Float = (ln(value.toDouble()) / ln(2.0)).toFloat()
