package com.cameraman.app

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.pow

/**
 * Edits applied after the shot, like a blogger's preset. Every value is -1..1 except [exposure]
 * (in stops) and [vignette] (0..1). Starting values from the notes in smart-camera-looks.md; tune on a phone.
 */
data class PhotoLook(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    /** Positive = warmer (more orange), negative = cooler (more blue). */
    val warmth: Float = 0f,
    /** Positive = magenta, negative = green. */
    val tint: Float = 0f,
    val saturation: Float = 0f,
    /** How much the corners are darkened. */
    val vignette: Float = 0f
) {
    /** Exposure, contrast, warmth, tint and saturation: works on the live preview and the saved photo. */
    fun colorMatrix(): ColorMatrix {
        val brightness = 2f.pow(exposure)
        val contrastScale = 1f + contrast
        val contrastOffset = 128f * (1f - contrastScale)
        val red = brightness * (1f + warmth * 0.3f)
        val green = brightness * (1f - tint * 0.2f)
        val blue = brightness * (1f - warmth * 0.3f)
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

    /** True when highlights, shadows or vignette need the slower per-pixel pass on the saved photo. */
    val needsPixelPass: Boolean get() = highlights != 0f || shadows != 0f || vignette != 0f

    /** Applies highlights, shadows and vignette to [bitmap] in place, one row at a time to save memory. */
    fun applyPixelPass(bitmap: Bitmap) {
        val toneCurve = IntArray(256) { level ->
            val value = level / 255f
            val highlightWeight = ((value - 0.5f) / 0.5f).coerceIn(0f, 1f).pow(2)
            val shadowWeight = ((0.5f - value) / 0.5f).coerceIn(0f, 1f).pow(2)
            val adjusted = value + highlights * 0.25f * highlightWeight + shadows * 0.25f * shadowWeight
            (adjusted.coerceIn(0f, 1f) * 255f).toInt()
        }
        val width = bitmap.width
        val height = bitmap.height
        val centerX = width / 2f
        val centerY = height / 2f
        val farthest = centerX * centerX + centerY * centerY
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val dy = y - centerY
            for (x in 0 until width) {
                val dx = x - centerX
                val darken = 1f - vignette * 0.5f * (dx * dx + dy * dy) / farthest
                val pixel = row[x]
                val red = (toneCurve[(pixel shr 16) and 0xFF] * darken).toInt()
                val green = (toneCurve[(pixel shr 8) and 0xFF] * darken).toInt()
                val blue = (toneCurve[pixel and 0xFF] * darken).toInt()
                row[x] = (pixel and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }
}

object Looks {
    val BrightAiry = PhotoLook(exposure = 0.3f, contrast = -0.2f, highlights = -0.4f, shadows = 0.4f, warmth = 0.05f, saturation = -0.1f)
    val WarmGolden = PhotoLook(highlights = -0.6f, shadows = 0.5f, warmth = 0.2f, tint = 0.05f, saturation = 0.1f, vignette = 0.15f)
    val DarkMoody = PhotoLook(contrast = -0.1f, highlights = -0.5f, shadows = -0.1f, saturation = -0.2f, vignette = 0.25f)
    val CleanMinimal = PhotoLook(exposure = 0.3f, contrast = 0.15f, saturation = -0.15f)
    val VividScene = PhotoLook(contrast = 0.1f, highlights = -0.3f, shadows = 0.2f, saturation = 0.15f)
    val Vintage = PhotoLook(contrast = -0.15f, shadows = 0.2f, warmth = 0.15f, saturation = -0.25f, vignette = 0.3f)

    /** From the Xiaohongshu pet recipe: exposure +8, contrast -8, saturation +15, highlights +8, shadows -5, temperature -5. */
    val Pet = PhotoLook(exposure = 0.15f, contrast = -0.08f, highlights = 0.08f, shadows = -0.05f, warmth = -0.05f, saturation = 0.15f)
}
