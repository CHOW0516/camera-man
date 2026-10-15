package com.cameraman.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.camera.core.ImageProxy

/** Color filters, applied the same way to the live preview and to the saved photo. */
enum class PhotoFilter {
    Original, Warm, Cool, Vivid, BlackAndWhite, Fade;

    /** Null means no change. */
    fun colorMatrix(): ColorMatrix? = when (this) {
        Original -> null
        Warm -> ColorMatrix().apply { setScale(1.1f, 1.0f, 0.85f, 1f) }
        Cool -> ColorMatrix().apply { setScale(0.9f, 1.0f, 1.15f, 1f) }
        Vivid -> ColorMatrix().apply { setSaturation(1.6f) }
        BlackAndWhite -> ColorMatrix().apply { setSaturation(0f) }
        Fade -> ColorMatrix().apply {
            setSaturation(0.7f)
            // Lower contrast and lift the shadows.
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.8f, 0f, 0f, 0f, 30f,
                        0f, 0.8f, 0f, 0f, 30f,
                        0f, 0f, 0.8f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    /** Paint for the preview layer, or null for no filter. */
    fun layerPaint(): Paint? = colorMatrix()?.let { matrix ->
        Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    }
}

/** Row of small sample pictures, one per filter. The chosen one has a white ring. */
@Composable
fun FilterPicker(selected: PhotoFilter, onSelect: (PhotoFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (filter in PhotoFilter.entries) {
            val ringColor = if (filter == selected) Color.White else Color.White.copy(alpha = 0.3f)
            val ringWidth = if (filter == selected) 3.dp else 1.dp
            val composeFilter = filter.colorMatrix()?.let {
                androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(it.array))
            }
            ComposeCanvas(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(ringWidth, ringColor, CircleShape)
                    .clickable { onSelect(filter) }
            ) {
                // A tiny "scene": sky, skin tone and grass, drawn through the filter.
                val bandHeight = size.height / 3
                val sceneColors = listOf(Color(0xFF64B5F6), Color(0xFFE0A47A), Color(0xFF66BB6A))
                sceneColors.forEachIndexed { index, bandColor ->
                    drawRect(
                        bandColor,
                        topLeft = Offset(0f, bandHeight * index),
                        size = Size(size.width, bandHeight),
                        colorFilter = composeFilter
                    )
                }
            }
        }
    }
}

/** Turns a captured frame into an upright bitmap with the filter applied. */
fun filteredBitmap(image: ImageProxy, filter: PhotoFilter): Bitmap {
    val source = image.toBitmap()
    val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotation, true)
    val result = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
    Canvas(result).drawBitmap(upright, 0f, 0f, filter.layerPaint())
    if (upright !== source) upright.recycle()
    source.recycle()
    return result
}

/** Saves a bitmap as a JPEG in Pictures/CameraMan. Returns false if it could not be saved. */
fun saveJpeg(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraMan")
        }
    }
    val resolver = context.contentResolver
    val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
    val written = resolver.openOutputStream(uri)?.use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
    } ?: false
    if (!written) resolver.delete(uri, null, null)
    return written
}
