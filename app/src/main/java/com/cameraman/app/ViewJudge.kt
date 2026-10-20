package com.cameraman.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.Closeable
import java.io.File

private const val EMBEDDER_MODEL = "models/mobilenet_v3_small.tflite"
private const val SEGMENTER_MODEL = "models/deeplab_v3.tflite"
private const val BUILT_IN_REFERENCES = "references"
private const val USER_REFERENCES = "references"
private const val REFERENCE_SIZE = 320

// Reference match is mapped onto 0..1 between these similarities. Measured on the 29 built-in references:
// unrelated pictures (text pages) reach about 0.1–0.27, a typical reference's nearest other reference about 0.45.
private const val MATCH_FLOOR = 0.15f
private const val MATCH_CEILING = 0.55f

// Weights of the spot score parts (they add up to 1). Starting values; tune on a phone.
private const val REFERENCE_WEIGHT = 0.4f
private const val CLEAN_WEIGHT = 0.3f
private const val STRUCTURE_WEIGHT = 0.15f
private const val LIGHT_WEIGHT = 0.15f

/** This much of the view covered by people, cars or objects counts as fully distracting. */
private const val FULL_DISTRACTION = 0.15f

/** Where the person being photographed stands when there is one: people here are not distractions. */
private const val SUBJECT_LEFT = 0.3f
private const val SUBJECT_RIGHT = 0.7f

// DeepLab v3 (PASCAL VOC) class numbers.
private const val PERSON = 15
private val VEHICLES = setOf(2, 6, 7, 14, 19) // bicycle, bus, car, motorbike, train
private val OBJECTS = setOf(5, 9, 18, 20) // bottle, chair, sofa, TV

/** Scenes where bottles, chairs and tables are part of the picture, not clutter. */
private val tableScenes = setOf(Scene.Food, Scene.Product, Scene.MoodyStillLife)

/**
 * What the AI models see in one view. [referenceMatch] is 0..1 (how much it looks like the reference photos),
 * [distraction] is the share of the view covered by people, cars or clutter (0..1), centered at [distractionCenter]
 * (0..1 upright image coordinates). [embedding] is used to compare views with each other.
 */
class ViewJudgement(
    val referenceMatch: Float,
    val closestReference: String?,
    val distraction: Float,
    val distractionCenter: PointF?,
    val embedding: FloatArray
)

/**
 * Judges camera views with two small on-device models: an image embedder (compares the view with influencer
 * reference photos) and a segmenter (finds people, cars and clutter). Not thread-safe for [judge]; call it from
 * one thread. [loadReferences] may run on another thread.
 */
class ViewJudge(context: Context) : Closeable {
    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(EMBEDDER_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .build()
    )
    private val segmenter = ImageSegmenter.createFromOptions(
        context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(SEGMENTER_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
    )

    /** Embeddings of the built-in and user-added reference photos, by file name. */
    @Volatile
    private var references: List<Pair<String, FloatArray>> = emptyList()

    /** (Re)reads the built-in reference photos and the ones the user added. Slow: call off the main thread. */
    fun loadReferences(context: Context) {
        val loaded = mutableListOf<Pair<String, FloatArray>>()
        val assets = context.assets
        for (name in assets.list(BUILT_IN_REFERENCES).orEmpty()) {
            val bitmap = assets.open("$BUILT_IN_REFERENCES/$name").use { BitmapFactory.decodeStream(it) } ?: continue
            loaded += name to embed(bitmap)
            bitmap.recycle()
        }
        for (file in userReferenceFolder(context).listFiles().orEmpty()) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            loaded += file.name to embed(bitmap)
            bitmap.recycle()
        }
        references = loaded
    }

    /** A length-1 description of the picture; similar pictures have a high dot product ([similarity]). */
    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(bitmap).build()).embeddingResult().embeddings()[0].floatEmbedding()

    /**
     * Judges one upright view. [hasSubject] means someone is being photographed in the middle (their body is not a
     * distraction); [scene] decides whether bottles and chairs count as clutter.
     */
    fun judge(bitmap: Bitmap, hasSubject: Boolean, scene: Scene?): ViewJudgement {
        val embedding = embed(bitmap)
        val closest = references.maxByOrNull { similarity(it.second, embedding) }
        val match = closest?.let { similarity(it.second, embedding) } ?: 0f

        val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
        val mask = result.categoryMask().get()
        val classes = ByteBufferExtractor.extract(mask)
        val width = mask.width
        val height = mask.height
        val distractingClasses = VEHICLES + PERSON + (if (scene in tableScenes) emptySet() else OBJECTS)
        var count = 0
        var sumX = 0f
        var sumY = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val category = classes.get(y * width + x).toInt() and 0xFF
                if (category !in distractingClasses) continue
                val relativeX = x.toFloat() / width
                if (category == PERSON && hasSubject && relativeX in SUBJECT_LEFT..SUBJECT_RIGHT) continue
                count++
                sumX += relativeX
                sumY += y.toFloat() / height
            }
        }
        mask.close()
        val center = if (count == 0) null else PointF(sumX / count, sumY / count)
        return ViewJudgement(
            referenceMatch = ((match - MATCH_FLOOR) / (MATCH_CEILING - MATCH_FLOOR)).coerceIn(0f, 1f),
            closestReference = closest?.first,
            distraction = count.toFloat() / (width * height),
            distractionCenter = center,
            embedding = embedding
        )
    }

    override fun close() {
        embedder.close()
        segmenter.close()
    }
}

/** Dot product of two normalized embeddings: 1 = the same picture, around 0 = unrelated. */
fun similarity(first: FloatArray, second: FloatArray): Float {
    var total = 0f
    for (index in first.indices) total += first[index] * second[index]
    return total
}

/**
 * Spot score 0..100 for one view: looks like the reference photos, no people/cars/clutter, some structure
 * (plain areas, straight lines, symmetry), and enough light for the scene.
 */
fun spotScore(judgement: ViewJudgement, reading: FrameReading, tooDark: Boolean): Int {
    val distraction = (judgement.distraction / FULL_DISTRACTION).coerceIn(0f, 1f)
    val busyness = (reading.subjectClutter / 25f).coerceIn(0f, 1f)
    val clean = 1f - distraction * 0.6f - busyness * 0.4f
    // Even messy views are about 70% symmetric on this scale, so only the part above counts.
    val mirrored = ((reading.symmetry - 0.7f) / 0.25f).coerceIn(0f, 1f)
    val structure = reading.plainFraction * 0.5f + reading.lineStrength * 0.25f + mirrored * 0.25f
    val light = when {
        tooDark -> 0f
        reading.highlightFraction > 0.15f -> 0.5f
        else -> 1f
    }
    val score = REFERENCE_WEIGHT * judgement.referenceMatch + CLEAN_WEIGHT * clean +
        STRUCTURE_WEIGHT * structure + LIGHT_WEIGHT * light
    return (score * 100).toInt().coerceIn(0, 100)
}

fun userReferenceFolder(context: Context): File = File(context.filesDir, USER_REFERENCES).apply { mkdirs() }

/** Copies photos the user picked into the app, shrunk to reference size. Returns how many were saved. */
fun saveReferencePhotos(context: Context, uris: List<Uri>): Int {
    var saved = 0
    for (uri in uris) {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: continue
        val scale = REFERENCE_SIZE.toFloat() / maxOf(original.width, original.height)
        val small = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        File(userReferenceFolder(context), "user_${System.currentTimeMillis()}_$saved.jpg").outputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }
        if (small !== original) small.recycle()
        original.recycle()
        saved++
    }
    return saved
}
