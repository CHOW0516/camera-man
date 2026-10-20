package com.cameraman.app

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How many of the best views scout mode keeps. */
const val SCOUT_VIEWS_KEPT = 3

/** Views at least this similar count as the same view (only the better one is kept). */
const val SAME_VIEW_SIMILARITY = 0.85f

/** One view seen while scouting: a small photo of it, its score, and which way the camera pointed. */
class ScoutView(
    val bitmap: Bitmap,
    val score: Int,
    val embedding: FloatArray,
    val headingDegrees: Float?,
    val pitchDegrees: Float?
)

/** Scout mode: the user walks around and the best few different views are kept. */
class ScoutSession {
    /** Best first; observable from Compose. */
    var bestViews by mutableStateOf<List<ScoutView>>(emptyList())
        private set

    fun offer(view: ScoutView) {
        val sameView = bestViews.firstOrNull { similarity(it.embedding, view.embedding) >= SAME_VIEW_SIMILARITY }
        val kept = when {
            sameView == null -> bestViews + view
            view.score > sameView.score -> bestViews - sameView + view
            else -> return
        }
        bestViews = kept.sortedByDescending { it.score }.take(SCOUT_VIEWS_KEPT)
    }
}

/** Degrees to turn from [currentHeading] to [targetHeading]: positive = turn right, -180..180. */
fun turnDegrees(currentHeading: Float, targetHeading: Float): Float =
    ((targetHeading - currentHeading + 540f) % 360f) - 180f
