package com.cameraman.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.pose.PoseDetector
import java.io.ByteArrayOutputStream
import kotlin.math.abs

private const val GRID_COLUMNS = 48
private const val GRID_ROWS = 36
private const val HIGHLIGHT_LEVEL = 240

/** Neighboring cells closer than this count as one plain area (sky, wall). */
private const val PLAIN_STEP = 6

/** A brightness jump at least this big counts as part of a sharp line. */
private const val LINE_STEP = 25

/** Snapshots for the scan are taken every this many frames, and sized to this many pixels across. */
private const val SNAPSHOT_EVERY_FRAMES = 4
private const val SNAPSHOT_WIDTH = 320

/** Image labels (what is in the scene) are found every this many frames, and kept when at least this sure. */
private const val LABEL_EVERY_FRAMES = 10
private const val MIN_LABEL_CONFIDENCE = 0.6f

/** Where the person will stand in a scanned view: the middle of the frame. */
private val SUBJECT_AREA = RectF(0.3f, 0.2f, 0.7f, 0.95f)

/** A small JPEG of one frame, kept by scan mode to show Gemini the best views. */
class Snapshot(val jpeg: ByteArray)

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
    val imageAspectRatio: Float,
    /** Body joints found by pose detection; null when pose mode is off or nobody was found. */
    val bodyPoints: Map<Joint, PointF>? = null,
    /** Busyness where a person would stand (same scale as [clutter]). */
    val subjectClutter: Float = 0f,
    /** 0..1: how much of the view is plain areas like sky or walls. */
    val plainFraction: Float = 0f,
    /** 0..1: how alike the left and right halves are. */
    val symmetry: Float = 0f,
    /** 0..1: length of the longest straight vertical or horizontal line, as a share of the view. */
    val lineStrength: Float = 0f,
    /** Only taken while [SceneAnalyzer.takeSnapshots] is on, every few frames. */
    val snapshot: Snapshot? = null,
    /** What ML Kit sees in the scene ("Food", "Sky"...); only set every few frames, null otherwise. */
    val labels: List<String>? = null,
    /** Color cast on the face: positive = green (light bouncing off trees or grass), negative = magenta. */
    val faceTint: Float? = null
)

/** A joint must be at least this likely to be in the frame to be used. */
private const val MIN_JOINT_LIKELIHOOD = 0.5f

/**
 * Measures brightness on a small grid of the frame, then finds the largest face with ML Kit,
 * or the body's joints when [usePoseDetection] is on.
 * Runs on a background thread; [onReading] is called once per analyzed frame.
 */
class SceneAnalyzer(
    private val faceDetector: FaceDetector,
    private val poseDetector: PoseDetector,
    private val imageLabeler: ImageLabeler,
    private val onReading: (FrameReading) -> Unit
) : ImageAnalysis.Analyzer {

    /** Set from the main thread when the user turns pose mode on or off. */
    @Volatile
    var usePoseDetection = false

    /** Set while scanning, so the best views can be shown to Gemini. */
    @Volatile
    var takeSnapshots = false

    private var frameNumber = 0

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val lumaGrid = sampleGrid(imageProxy, plane = 0).rotatedUpright(rotationDegrees)
        val blueGrid = sampleGrid(imageProxy, plane = 1).rotatedUpright(rotationDegrees)
        val redGrid = sampleGrid(imageProxy, plane = 2).rotatedUpright(rotationDegrees)
        val isSideways = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isSideways) imageProxy.height else imageProxy.width
        val uprightHeight = if (isSideways) imageProxy.width else imageProxy.height

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        frameNumber++
        val snapshot = if (takeSnapshots && frameNumber % SNAPSHOT_EVERY_FRAMES == 0) {
            takeSnapshot(imageProxy, rotationDegrees)
        } else {
            null
        }

        fun report(faceBox: RectF?, bodyPoints: Map<Joint, PointF>?, labels: List<String>?) {
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
                    imageAspectRatio = uprightWidth.toFloat() / uprightHeight,
                    bodyPoints = bodyPoints,
                    subjectClutter = lumaGrid.edgeStrength(SUBJECT_AREA),
                    plainFraction = lumaGrid.plainFraction(PLAIN_STEP),
                    symmetry = lumaGrid.symmetry(),
                    lineStrength = lumaGrid.lineStrength(LINE_STEP),
                    snapshot = snapshot,
                    labels = labels,
                    // Green has both color values below the middle (128), magenta both above.
                    faceTint = faceBox?.let { 128f - (blueGrid.averageBrightness(it) + redGrid.averageBrightness(it)) / 2 }
                )
            )
        }

        fun findSubject(labels: List<String>?) {
            if (usePoseDetection) {
                poseDetector.process(inputImage)
                    .addOnSuccessListener { pose ->
                        val bodyPoints = Joint.entries.mapNotNull { joint ->
                            val landmark = pose.getPoseLandmark(joint.mlKitType)
                            if (landmark == null || landmark.inFrameLikelihood < MIN_JOINT_LIKELIHOOD) {
                                null
                            } else {
                                joint to PointF(landmark.position.x / uprightWidth, landmark.position.y / uprightHeight)
                            }
                        }.toMap()
                        report(faceBox = null, bodyPoints = bodyPoints.ifEmpty { null }, labels = labels)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                faceDetector.process(inputImage)
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
                        report(faceBox = faceBox, bodyPoints = null, labels = labels)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
        }

        if (frameNumber % LABEL_EVERY_FRAMES == 0) {
            imageLabeler.process(inputImage).addOnCompleteListener { task ->
                val labels = if (task.isSuccessful) {
                    task.result.filter { it.confidence >= MIN_LABEL_CONFIDENCE }.map { it.text }
                } else {
                    emptyList()
                }
                findSubject(labels)
            }
        } else {
            findSubject(labels = null)
        }
    }

    /** A small upright JPEG of the frame. Returns null if the frame could not be converted. */
    private fun takeSnapshot(imageProxy: ImageProxy, rotationDegrees: Int): Snapshot? = try {
        val frame = imageProxy.toBitmap()
        val scale = SNAPSHOT_WIDTH.toFloat() / maxOf(frame.width, frame.height)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        val small = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        val stream = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        small.recycle()
        frame.recycle()
        Snapshot(stream.toByteArray())
    } catch (exception: Exception) {
        null
    }

    /**
     * Samples one plane at evenly spaced points, in sensor orientation: plane 0 is brightness (Y),
     * 1 and 2 are the blue and red color differences (U and V, stored at half size).
     */
    private fun sampleGrid(imageProxy: ImageProxy, plane: Int): LumaGrid {
        val imagePlane = imageProxy.planes[plane]
        val buffer = imagePlane.buffer
        val scale = if (plane == 0) 1 else 2
        val values = IntArray(GRID_COLUMNS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val pixelY = (row + 0.5f) * imageProxy.height / GRID_ROWS / scale
            for (column in 0 until GRID_COLUMNS) {
                val pixelX = (column + 0.5f) * imageProxy.width / GRID_COLUMNS / scale
                val index = pixelY.toInt() * imagePlane.rowStride + pixelX.toInt() * imagePlane.pixelStride
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
     * Average brightness jump between neighboring cells inside [area] (0..1 coordinates).
     * Plain walls and sky score low, busy backgrounds (shelves, crowds, leaves) score high.
     */
    fun edgeStrength(area: RectF = RectF(0f, 0f, 1f, 1f)): Float {
        val firstColumn = (area.left * columns).toInt().coerceIn(0, columns - 1)
        val lastColumn = (area.right * columns).toInt().coerceIn(firstColumn, columns - 1)
        val firstRow = (area.top * rows).toInt().coerceIn(0, rows - 1)
        val lastRow = (area.bottom * rows).toInt().coerceIn(firstRow, rows - 1)
        var total = 0
        var count = 0
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val value = valueAt(column, row)
                if (column + 1 <= lastColumn) {
                    total += abs(value - valueAt(column + 1, row))
                    count++
                }
                if (row + 1 <= lastRow) {
                    total += abs(value - valueAt(column, row + 1))
                    count++
                }
            }
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    /** Share of cells that differ from their right and lower neighbors by less than [step]. */
    fun plainFraction(step: Int): Float {
        var plainCells = 0
        for (row in 0 until rows - 1) {
            for (column in 0 until columns - 1) {
                val value = valueAt(column, row)
                if (abs(value - valueAt(column + 1, row)) < step && abs(value - valueAt(column, row + 1)) < step) {
                    plainCells++
                }
            }
        }
        return plainCells.toFloat() / ((rows - 1) * (columns - 1))
    }

    /** 1 when the left half mirrors the right half exactly, lower as they differ. */
    fun symmetry(): Float {
        var totalDifference = 0
        for (row in 0 until rows) {
            for (column in 0 until columns / 2) {
                totalDifference += abs(valueAt(column, row) - valueAt(columns - 1 - column, row))
            }
        }
        return 1f - totalDifference.toFloat() / (rows * (columns / 2) * 255)
    }

    /**
     * Longest unbroken vertical or horizontal edge (brightness jump of at least [step]),
     * as a share of the view's height or width. Allows one cell of drift for slightly tilted lines.
     */
    fun lineStrength(step: Int): Float {
        fun verticalJump(column: Int, row: Int) =
            column in 0 until columns - 1 && abs(valueAt(column, row) - valueAt(column + 1, row)) >= step
        fun horizontalJump(column: Int, row: Int) =
            row in 0 until rows - 1 && abs(valueAt(column, row) - valueAt(column, row + 1)) >= step

        var longestVertical = 0
        for (startColumn in 0 until columns - 1) {
            var column = startColumn
            var run = 0
            for (row in 0 until rows) {
                val next = listOf(column, column - 1, column + 1).firstOrNull { verticalJump(it, row) }
                if (next == null) {
                    run = 0
                    column = startColumn
                } else {
                    run++
                    column = next
                    longestVertical = maxOf(longestVertical, run)
                }
            }
        }
        var longestHorizontal = 0
        for (startRow in 0 until rows - 1) {
            var row = startRow
            var run = 0
            for (column in 0 until columns) {
                val next = listOf(row, row - 1, row + 1).firstOrNull { horizontalJump(column, it) }
                if (next == null) {
                    run = 0
                    row = startRow
                } else {
                    run++
                    row = next
                    longestHorizontal = maxOf(longestHorizontal, run)
                }
            }
        }
        return maxOf(longestVertical.toFloat() / rows, longestHorizontal.toFloat() / columns)
    }

    fun fractionAtOrAbove(level: Int): Float = values.count { it >= level }.toFloat() / values.size
}
