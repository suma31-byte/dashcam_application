package com.boat.dashcamcompanion.detect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.boat.dashcamcompanion.net.InternetConnection
import com.boat.dashcamcompanion.vision.VisionVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Pothole DETECTION by Google Gemini (cloud) instead of the on-phone YOLO model.
 *
 * Each check sends the road picture to Gemini and gets pothole boxes back. Needs internet
 * (mobile data works while the phone is on the dashcam's Wi-Fi). Much slower than on-phone
 * detection (~1-5 s per picture) and limited by the API's request quota, so the detection loop
 * runs it only every few seconds. Uses the key and model set under Potholes found → Settings.
 */
class GeminiPotholeDetector(context: Context) : PotholeDetector {
    private val appContext = context.applicationContext

    @Volatile var lastInferenceMs = 0L
        private set
    /** Last problem (no key, no internet, quota…), shown in the read-out; null = OK. */
    @Volatile var lastError: String? = null
        private set

    override fun detect(roadStrip: Bitmap): List<Detection> {
        val start = SystemClock.uptimeMillis()
        val result = try {
            ask(roadStrip).also { lastError = null }
        } catch (e: Exception) {
            lastError = e.message
            Log.w(TAG, "Gemini detection failed: ${e.message}")
            emptyList()
        }
        lastInferenceMs = SystemClock.uptimeMillis() - start
        return result
    }

    private fun ask(img: Bitmap): List<Detection> {
        val key = VisionVerifier.geminiKey(appContext).ifBlank { error("no Gemini API key (Potholes found → Settings)") }
        val model = VisionVerifier.geminiModel(appContext)
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpeg(img))))
                .put(JSONObject().put("text", PROMPT)))))
            put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
        }
        val conn = InternetConnection.open(
            appContext, "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        ).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error(when (code) {
                    429 -> "Gemini request limit reached - checks will continue when allowed"
                    503 -> "Gemini busy - retrying"
                    else -> "Gemini answered $code: ${err.take(120)}"
                })
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            val text = reply.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
                .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONObject(text).optJSONArray("potholes") ?: return emptyList()
            val out = ArrayList<Detection>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = o.optJSONArray("box_2d") ?: continue
                if (b.length() < 4) continue
                // Gemini boxes: [ymin, xmin, ymax, xmax] on a 0-1000 scale
                val top = (b.getDouble(0) / 1000).toFloat().coerceIn(0f, 1f)
                val left = (b.getDouble(1) / 1000).toFloat().coerceIn(0f, 1f)
                val bottom = (b.getDouble(2) / 1000).toFloat().coerceIn(0f, 1f)
                val right = (b.getDouble(3) / 1000).toFloat().coerceIn(0f, 1f)
                val conf = o.optDouble("confidence", 0.8).toFloat()
                if (right - left > 0.005f && bottom - top > 0.005f && conf >= 0.4f) {
                    out += Detection(left, top, right, bottom, conf)
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun jpeg(img: Bitmap): String {
        val scale = minOf(1f, 1024f / img.width)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(img, (img.width * scale).toInt(), (img.height * scale).toInt(), true) else img
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (small !== img) small.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun close() {}

    private companion object {
        const val TAG = "GeminiDetector"
        const val PROMPT = """This image shows the road ahead of a vehicle (from a dashcam).
Find every POTHOLE on the road surface: a hole or depression in the road, dry, muddy or filled
with water. Do NOT mark cracks, road patches in good condition, manholes, speed breakers, shadows,
puddles on intact road or road markings.
Answer ONLY with JSON: {"potholes": [{"box_2d": [ymin, xmin, ymax, xmax], "confidence": 0.0-1.0}]}
box_2d values are integers 0-1000 relative to the image. Use an empty list if there is no pothole."""
    }
}

/** Which detector the app uses: Google Gemini (cloud, default) or the on-phone YOLO model. Saved. */
object DetectorMode {
    private const val PREFS = "detector_mode"
    /** Default: Gemini (on-phone YOLO remains available by switching it off). */
    fun useGemini(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gemini", true)
    fun setUseGemini(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gemini", on).apply()

    /** Gemini is slow and quota-limited: one check every few seconds. */
    const val GEMINI_INTERVAL_MS = 4_000L
}
