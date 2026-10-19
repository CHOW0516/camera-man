package com.cameraman.app

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.pow

/**
 * Edits applied after the shot, like a blogger's preset. Every value is -1..1 except [exposure]
 * (in stops), [vignette] and [sharpness] (0..1). Values come from notes/smart-camera-looks.md; tune on a phone.
 */
data class PhotoLook(
    val exposure: Float = 0f,
    /** Lifts or lowers the midtones. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    /** Positive = warmer (more orange), negative = cooler (more blue). */
    val warmth: Float = 0f,
    /** Positive = magenta, negative = green. */
    val tint: Float = 0f,
    val saturation: Float = 0f,
    /** How much the corners are darkened. */
    val vignette: Float = 0f,
    val sharpness: Float = 0f
) {
    /** Exposure, contrast, warmth, tint and saturation: works on the live preview and the saved photo. */
    fun colorMatrix(): ColorMatrix {
        val brightnessScale = 2f.pow(exposure)
        val contrastScale = 1f + contrast
        val contrastOffset = 128f * (1f - contrastScale)
        val red = brightnessScale * (1f + warmth * 0.3f)
        val green = brightnessScale * (1f - tint * 0.2f)
        val blue = brightnessScale * (1f - warmth * 0.3f)
        val matrix = ColorMatrix().apply { setSaturation(1f + saturation) }
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    red * contrastScale, 0f, 0f, 0f, contrastOffset,
                    0f, green * contrastScale, 0f, 0f, contrastOffset,
                    0f, 0f, blue * contrastScale, 0f, contrastOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return matrix
    }

    /** True when brightness, highlights, shadows, vignette or sharpness need the slower per-pixel pass on the saved photo. */
    val needsPixelPass: Boolean
        get() = brightness != 0f || highlights != 0f || shadows != 0f || vignette != 0f || sharpness != 0f

    /**
     * Applies sharpness, brightness, highlights, shadows and vignette to [bitmap] in place.
     * Works one row at a time (plus the rows above and below for sharpening) to save memory.
     */
    fun applyPixelPass(bitmap: Bitmap) {
        val toneCurve = IntArray(256) { level ->
            val value = level / 255f
            val highlightWeight = ((value - 0.5f) / 0.5f).coerceIn(0f, 1f).pow(2)
            val shadowWeight = ((0.5f - value) / 0.5f).coerceIn(0f, 1f).pow(2)
            // Brightness bends the middle of the curve and leaves black and white where they are.
            val midtones = value.pow(1f - brightness * 0.4f)
            val adjusted = midtones + highlights * 0.25f * highlightWeight + shadows * 0.25f * shadowWeight
            (adjusted.coerceIn(0f, 1f) * 255f).toInt()
        }
        val width = bitmap.width
        val height = bitmap.height
        val centerX = width / 2f
        val centerY = height / 2f
        val farthest = centerX * centerX + centerY * centerY
        val sharpenAmount = sharpness * 1.5f

        var above = IntArray(width)
        var current = IntArray(width)
        var below = IntArray(width)
        val output = IntArray(width)
        bitmap.getPixels(current, 0, width, 0, 0, width, 1)
        current.copyInto(above)
        for (y in 0 until height) {
            if (y + 1 < height) bitmap.getPixels(below, 0, width, 0, y + 1, width, 1) else current.copyInto(below)
            val dy = y - centerY
            for (x in 0 until width) {
                val dx = x - centerX
                val darken = 1f - vignette * 0.5f * (dx * dx + dy * dy) / farthest
                val left = current[maxOf(x - 1, 0)]
                val right = current[minOf(x + 1, width - 1)]
                var pixel = 0xFF000000.toInt()
                for (shift in intArrayOf(16, 8, 0)) {
                    val center = (current[x] shr shift) and 0xFF
                    // Sharpen: push each pixel away from the average of its four neighbors.
                    val neighbors = (((above[x] shr shift) and 0xFF) + ((below[x] shr shift) and 0xFF) +
                        ((left shr shift) and 0xFF) + ((right shr shift) and 0xFF)) / 4f
                    val sharpened = (center + sharpenAmount * (center - neighbors)).toInt().coerceIn(0, 255)
                    val channel = (toneCurve[sharpened] * darken).toInt().coerceIn(0, 255)
                    pixel = pixel or (channel shl shift)
                }
                output[x] = pixel
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1)
            // The original rows move up by one; the oldest array is reused for the next row below.
            val oldest = above
            above = current
            current = below
            below = oldest
        }
    }
}

/**
 * A look from a phone photo editor's sliders (Xiaomi, OPPO, iPhone, all -100..100), so the numbers
 * match the screenshots. Light (光感) counts as half brightness, vividness as half saturation,
 * and clarity as half sharpness; negative sharpness is ignored (the app only sharpens).
 */
fun editorLook(
    exposure: Int = 0,
    brightness: Int = 0,
    contrast: Int = 0,
    light: Int = 0,
    saturation: Int = 0,
    vividness: Int = 0,
    sharpness: Int = 0,
    highlights: Int = 0,
    shadows: Int = 0,
    temperature: Int = 0,
    clarity: Int = 0,
    tint: Int = 0,
    vignette: Int = 0
) = PhotoLook(
    exposure = exposure / 100f,
    brightness = (brightness + light * 0.5f) / 100f,
    contrast = contrast / 100f,
    highlights = highlights / 100f,
    shadows = shadows / 100f,
    warmth = temperature / 100f,
    tint = tint / 100f,
    saturation = (saturation + vividness * 0.5f) / 100f,
    vignette = vignette / 100f,
    sharpness = (maxOf(sharpness, 0) + maxOf(clarity, 0) * 0.5f) / 100f
)

object Looks {
    // From public preset guides (first notes section): no exact blogger numbers were available.
    val BrightAiry = PhotoLook(exposure = 0.3f, contrast = -0.2f, highlights = -0.4f, shadows = 0.4f, warmth = 0.05f, saturation = -0.1f)
    val WarmGolden = PhotoLook(highlights = -0.6f, shadows = 0.5f, warmth = 0.2f, tint = 0.05f, saturation = 0.1f, vignette = 0.15f)
    val DarkMoody = PhotoLook(contrast = -0.1f, highlights = -0.5f, shadows = -0.1f, saturation = -0.2f, vignette = 0.25f)
    val CleanMinimal = PhotoLook(exposure = 0.3f, contrast = 0.15f, saturation = -0.15f)
    val VividScene = PhotoLook(contrast = 0.1f, highlights = -0.3f, shadows = 0.2f, saturation = 0.15f)
    val Vintage = PhotoLook(contrast = -0.15f, shadows = 0.2f, warmth = 0.15f, saturation = -0.25f, vignette = 0.3f)

    // Exact slider values from the Xiaohongshu screenshots (second notes section).
    val Moon = editorLook(exposure = -12, brightness = 10, contrast = -5, light = 10, saturation = 9, sharpness = 7, highlights = -18, shadows = 4, temperature = 5, clarity = 10, tint = 5)
    val Architecture = editorLook(exposure = -12, brightness = 10, contrast = -10, light = 10, saturation = 20, sharpness = -8, highlights = -16, shadows = -8, temperature = 5, clarity = 5, tint = 20)
    val NightCity = editorLook(contrast = 25, light = 20, saturation = -5, sharpness = 14, highlights = -16, shadows = 5, temperature = 15, clarity = 29, tint = 35)
    val SunRays = editorLook(exposure = -3, brightness = 5, contrast = 8, saturation = -16, sharpness = -4, highlights = -20, shadows = -10, clarity = 5, tint = 10)
    val Pet = editorLook(exposure = 8, brightness = 10, contrast = 10, light = 15, saturation = 10, sharpness = 20, highlights = 8, shadows = -5, temperature = 6, clarity = 10, tint = 20)
    val FujiFilm = editorLook(exposure = -6, brightness = -5, contrast = 5, light = 8, saturation = -10, sharpness = -5, highlights = -4, shadows = -8, temperature = -10, clarity = 10, tint = 5)
    val Sea = editorLook(exposure = -8, brightness = -18, contrast = 10, vividness = 10, saturation = 9, sharpness = 7, highlights = -15, shadows = 4, temperature = -20)
    val Sunset = editorLook(exposure = 3, brightness = 12, contrast = 7, vividness = 10, saturation = 14, sharpness = 7, highlights = -18, shadows = 4, temperature = 22)
    val Afterglow = editorLook(exposure = 8, brightness = -19, contrast = 20, vividness = 10, saturation = 17, sharpness = 15, highlights = 16, shadows = -5, temperature = -9)

    /** iPhone values (black point +6 counts as darker shadows); the vignette stands in for "darken the background". */
    val MoodyStillLife = editorLook(vividness = -27, highlights = 25, shadows = -27, contrast = 10, sharpness = 29, clarity = 10, vignette = 35)
}
