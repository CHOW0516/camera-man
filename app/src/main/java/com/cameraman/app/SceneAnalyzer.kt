package com.cameraman.app

import android.annotation.SuppressLint
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import kotlin.math.abs

private const val GRID_COLUMNS = 48
private const val GRID_ROWS = 36
private const val HIGHLIGHT_LEVEL = 240

/**
 * What one camera frame tells us, in upright image coordinates (0..1, not mirrored).
 * [imageAspectRatio] is upright width / height, needed to map onto the preview.
 */
data class FrameReading(
    val frameBrightness: Float,
    val highlightFraction: Float,
    val faceBox: RectF?,
    val faceBrightness: Float?,
    val faceLeftBrightness: Float?,
    val faceRightBrightness: Float?,
    val clutter: Float,
    val imageAspectRatio: Float
)

/**
 * Measures brightness on a small grid of the frame and finds the largest face with ML Kit.
 * Runs on a background thread; [onReading] is called once per analyzed frame.
 */
class SceneAnalyzer(
    private val faceDetector: FaceDetector,
    private val onReading: (FrameReading) -> Unit
) : ImageAnalysis.Analyzer {

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val lumaGrid = sampleLumaGrid(imageProxy).rotatedUpright(rotationDegrees)
        val isSideways = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isSideways) imageProxy.height else imageProxy.width
        val uprightHeight = if (isSideways) imageProxy.width else imageProxy.height

        faceDetector.process(InputImage.fromMediaImage(mediaImage, rotationDegrees))
            .addOnSuccessListener { faces ->
                val largestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val faceBox = largestFace?.boundingBox?.let { box ->
                    RectF(
                        box.left.toFloat() / uprightWidth,
                        box.top.toFloat() / uprightHeight,
                        box.right.toFloat() / uprightWidth,
                        box.bottom.toFloat() / uprightHeight
                    )
                }
                onReading(
                    FrameReading(
                        frameBrightness = lumaGrid.averageBrightness(),
                        highlightFraction = lumaGrid.fractionAtOrAbove(HIGHLIGHT_LEVEL),
                        faceBox = faceBox,
                        faceBrightness = faceBox?.let { lumaGrid.averageBrightness(it) },
                        faceLeftBrightness = faceBox?.let {
                            lumaGrid.averageBrightness(RectF(it.left, it.top, it.centerX(), it.bottom))
                        },
                        faceRightBrightness = faceBox?.let {
                            lumaGrid.averageBrightness(RectF(it.centerX(), it.top, it.right, it.bottom))
                        },
                        clutter = lumaGrid.edgeStrength(),
                        imageAspectRatio = uprightWidth.toFloat() / uprightHeight
                    )
                )
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Samples the Y (brightness) plane at evenly spaced points, in sensor orientation. */
    private fun sampleLumaGrid(imageProxy: ImageProxy): LumaGrid {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val values = IntArray(GRID_COLUMNS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val pixelY = (row + 0.5f) * imageProxy.height / GRID_ROWS
            for (column in 0 until GRID_COLUMNS) {
                val pixelX = (column + 0.5f) * imageProxy.width / GRID_COLUMNS
                val index = pixelY.toInt() * yPlane.rowStride + pixelX.toInt() * yPlane.pixelStride
                values[row * GRID_COLUMNS + column] = buffer.get(index).toInt() and 0xFF
            }
        }
        return LumaGrid(GRID_COLUMNS, GRID_ROWS, values)
    }
}

/** A small brightness map (values 0..255), stored row by row. */
class LumaGrid(val columns: Int, val rows: Int, private val values: IntArray) {

    fun valueAt(column: Int, row: Int): Int = values[row * columns + column]

    /** Rotates the grid clockwise by [rotationDegrees] (0, 90, 180 or 270) so it is upright. */
    fun rotatedUpright(rotationDegrees: Int): LumaGrid = when (rotationDegrees) {
        90 -> LumaGrid(rows, columns, IntArray(values.size) { index ->
            val newColumn = index % rows
            val newRow = index / rows
            valueAt(column = newRow, row = rows - 1 - newColumn)
        })
        180 -> LumaGrid(columns, rows, IntArray(values.size) { index ->
            values[values.size - 1 - index]
        })
        270 -> LumaGrid(rows, columns, IntArray(values.size) { index ->
            val newColumn = index % rows
            val newRow = index / rows
            valueAt(column = columns - 1 - newRow, row = newColumn)
        })
        else -> this
    }

    fun averageBrightness(): Float = values.average().toFloat()

    /** Average brightness inside [area], given in 0..1 coordinates. */
    fun averageBrightness(area: RectF): Float {
        val firstColumn = (area.left * columns).toInt().coerceIn(0, columns - 1)
        val lastColumn = (area.right * columns).toInt().coerceIn(firstColumn, columns - 1)
        val firstRow = (area.top * rows).toInt().coerceIn(0, rows - 1)
        val lastRow = (area.bottom * rows).toInt().coerceIn(firstRow, rows - 1)
        var total = 0
        var count = 0
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                total += valueAt(column, row)
                count++
            }
        }
        return total.toFloat() / count
    }

    /**
     * Average brightness jump between neighboring cells. Plain walls and sky score low,
     * busy backgrounds (shelves, crowds, leaves) score high.
     */
    fun edgeStrength(): Float {
        var total = 0
        var count = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val value = valueAt(column, row)
                if (column + 1 < columns) {
                    total += abs(value - valueAt(column + 1, row))
                    count++
                }
                if (row + 1 < rows) {
                    total += abs(value - valueAt(column, row + 1))
                    count++
                }
            }
        }
        return total.toFloat() / count
    }

    fun fractionAtOrAbove(level: Int): Float = values.count { it >= level }.toFloat() / values.size
}
