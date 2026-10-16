package com.cameraman.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Always points to Google's newest Flash model, which is on the free tier. */
private const val GEMINI_MODEL = "gemini-flash-latest"
private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
private const val TIMEOUT_MILLIS = 15_000

/** How many of the best-scoring views are shown to Gemini. */
const val GEMINI_CANDIDATES = 3

private const val PREFERENCES = "camera_man"
private const val KEY_GEMINI = "gemini_api_key"

private const val PROMPT = """These photos are views from one spot, numbered in order starting at 1.
A person will stand in front of the camera for an influencer-style portrait, with the view behind them.
Pick the view that makes the best background: clean and not messy, with clear structure
(lines, symmetry, framing, open sky or plain walls) and pleasing light.
Answer only with JSON like {"best": 1}."""

fun loadGeminiKey(context: Context): String? =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_GEMINI, null)?.ifBlank { null }

fun saveGeminiKey(context: Context, key: String) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY_GEMINI, key.trim()).apply()
}

/**
 * Asks Gemini which view is the best background. Returns the index into [views],
 * or null when the call fails (no network, bad key, quota used up, odd answer).
 * Blocks while waiting, so call it off the main thread.
 */
fun askGeminiForBestView(apiKey: String, views: List<ScanView>): Int? {
    return try {
        requestBestView(apiKey, views)
    } catch (exception: Exception) {
        null
    }
}

private fun requestBestView(apiKey: String, views: List<ScanView>): Int? {
    val parts = JSONArray().put(JSONObject().put("text", PROMPT))
    for (view in views) {
        val jpeg = view.snapshot?.jpeg ?: return null
        parts.put(
            JSONObject().put(
                "inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            )
        )
    }
    val request = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    val connection = URL(GEMINI_URL).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-goog-api-key", apiKey)
        connection.outputStream.use { it.write(request.toString().toByteArray()) }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

        val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val answer = response.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val bestIndex = JSONObject(answer).getInt("best") - 1
        return bestIndex.takeIf { it in views.indices }
    } finally {
        connection.disconnect()
    }
}
