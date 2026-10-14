package com.cameraman.app

import android.graphics.PointF
import android.graphics.RectF

/**
 * Maps a point from upright image coordinates to screen coordinates (both 0..1).
 * The preview fills the screen and crops the image's overflow evenly on both sides;
 * the front camera preview is mirrored, like a selfie.
 */
fun toScreenPoint(
    imagePoint: PointF,
    imageAspectRatio: Float,
    viewAspectRatio: Float,
    isFrontCamera: Boolean
): PointF {
    // Visible part of the image, as a fraction of its width and height.
    val visibleWidth = if (imageAspectRatio > viewAspectRatio) viewAspectRatio / imageAspectRatio else 1f
    val visibleHeight = if (imageAspectRatio > viewAspectRatio) 1f else imageAspectRatio / viewAspectRatio
    val x = (imagePoint.x - (1f - visibleWidth) / 2) / visibleWidth
    val y = (imagePoint.y - (1f - visibleHeight) / 2) / visibleHeight
    return PointF(if (isFrontCamera) 1f - x else x, y)
}

/** Same as [toScreenPoint], for a box. */
fun toScreenCoordinates(
    imageBox: RectF,
    imageAspectRatio: Float,
    viewAspectRatio: Float,
    isFrontCamera: Boolean
): RectF {
    val topLeft = toScreenPoint(PointF(imageBox.left, imageBox.top), imageAspectRatio, viewAspectRatio, isFrontCamera)
    val bottomRight = toScreenPoint(PointF(imageBox.right, imageBox.bottom), imageAspectRatio, viewAspectRatio, isFrontCamera)
    return RectF(
        minOf(topLeft.x, bottomRight.x),
        topLeft.y,
        maxOf(topLeft.x, bottomRight.x),
        bottomRight.y
    )
}
