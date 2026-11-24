package com.boat.dashcamcompanion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.boat.dashcamcompanion.detect.PotholeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Second check of each pothole photo by a LOCAL vision model (Ollama on our own PC/server),
 * instead of OpenAI as in pothole-reporter. Fills in damage type, assessment, size and
 * description, and marks false positives ("not road damage").
 *
 * The phone must reach the server (same office Wi-Fi). While on the dashcam's Wi-Fi it can't,
 * so verification runs afterwards from the Potholes screen ("Verify with local model").
 * Same prompt and answer format as tools/vision_check.py.
 */
object VisionVerifier {
    private const val TAG = "VisionVerifier"
    const val PROMPT_VERSION = "v1"
    private const val PREFS = "vision"

    private val PROMPT = """
        You are checking a photo from a vehicle dashcam for road damage.
        A red rectangle (if present) marks where an automatic detector thinks there is a pothole.
        Look at the road surface inside and around the red rectangle and answer ONLY with JSON:
        - is_road_damage: true if there is real road damage there, false for shadows, patches in good
          condition, manholes, speed breakers, road markings, wet road or anything else.
        - damage_type: one of "pothole cavity", "failed repair", "surface breakup", "rut or depression", "none"
        - size: one of "small", "medium", "large", "none"
        - description: one short sentence, e.g. "Deep pothole in the left lane near the kerb"
        - confidence: number from 0 to 1
    """.trimIndent()

    private fun schema() = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("is_road_damage", JSONObject().put("type", "boolean"))
            put("damage_type", JSONObject().put("type", "string").put("enum",
                JSONArray(listOf("pothole cavity", "failed repair", "surface breakup", "rut or depression", "none"))))
            put("size", JSONObject().put("type", "string").put("enum", JSONArray(listOf("small", "medium", "large", "none"))))
            put("description", JSONObject().put("type", "string"))
            put("confidence", JSONObject().put("type", "number"))
        })
        put("required", JSONArray(listOf("is_road_damage", "damage_type", "size", "description", "confidence")))
    }

    // ---------------------------------------------------------------- settings

    fun serverUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "http://192.168.20.186:11434")!!

    fun model(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", "qwen2.5vl:7b")!!

    // ---- provider: "gemini" (Google cloud API, needs internet) or "ollama" (own PC/server)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun provider(context: Context): String = prefs(context).getString("provider", "gemini")!!
    fun geminiKey(context: Context): String = prefs(context).getString("gemini_key", "")!!
    fun geminiModel(context: Context): String = prefs(context).getString("gemini_model", "gemini-3.7-flash")!!
    /** Check every new pothole automatically as soon as it is logged (when there is internet). */
    fun autoVerify(context: Context): Boolean = prefs(context).getBoolean("auto", true)

    fun saveGeminiSettings(context: Context, key: String, model: String, auto: Boolean) {
        prefs(context).edit().putString("gemini_key", key.trim()).putString("gemini_model", model.trim())
            .putBoolean("auto", auto).apply()
    }

    fun setProvider(context: Context, provider: String) = prefs(context).edit().putString("provider", provider).apply()

    /** Short name of the model in use, for the UI. */
    fun activeModel(context: Context): String =
        if (provider(context) == "gemini") geminiModel(context) else model(context)

    fun isConfigured(context: Context): Boolean =
        if (provider(context) == "gemini") geminiKey(context).isNotBlank() else serverUrl(context).isNotBlank()

    fun saveSettings(context: Context, server: String, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", server.trim().trimEnd('/'))
            .putString("model", model.trim())
            .apply()
    }

    // ---------------------------------------------------------------- check one pothole

    /** Checks one saved pothole and stores the result. Throws with a readable message on failure. */
    suspend fun verify(context: Context, saved: PotholeJson.Saved): PotholeJson.VisionResult =
        if (provider(context) == "gemini") verifyGemini(context, saved) else verifyOllama(context, saved)

    // ---------------------------------------------------------------- Google Gemini (cloud)

    private suspend fun verifyGemini(context: Context, saved: PotholeJson.Saved): PotholeJson.VisionResult = withContext(Dispatchers.IO) {
        val photo = saved.frame ?: saved.strip ?: error("no photo saved for this pothole")
        val key = geminiKey(context).ifBlank { error("no Gemini API key set (Potholes found → Settings)") }
        val model = geminiModel(context)
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", encode(photo.path))))
                .put(JSONObject().put("text", PROMPT + "\nUse exactly these JSON keys: is_road_damage, damage_type, size, description, confidence.")))))
            put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
        }
        var lastError = ""
        for (attempt in 0 until 3) {
            // over a network with internet, not the dashcam's Wi-Fi the app may be bound to
            val conn = com.boat.dashcamcompanion.net.InternetConnection.open(
                context, "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            ).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code == 429 || code == 503 || code == 500) {          // busy / limit: wait and retry
                    lastError = "Gemini busy (HTTP $code)"
                    Thread.sleep(5_000L * (attempt + 1) * (attempt + 1))
                    continue
                }
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    error("Gemini answered $code: ${err.take(200)}")
                }
                val reply = JSONObject(conn.inputStream.bufferedReader().readText())
                val text = reply.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
                    .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val answer = JSONObject(text)
                val result = PotholeJson.VisionResult(
                    isRoadDamage = answer.optBoolean("is_road_damage"),
                    damageType = answer.optString("damage_type", "none"),
                    size = answer.optString("size", "none"),
                    description = answer.optString("description"),
                    confidence = answer.optDouble("confidence", 0.0).toFloat(),
                    provider = "Cloud: $model (Google Gemini)",
                    promptVersion = PROMPT_VERSION,
                    requestId = requestId,
                    checkedAtMs = System.currentTimeMillis()
                )
                PotholeJson.saveVision(saved.folder, saved.event.id, result)
                Log.i(TAG, "Gemini: pothole ${saved.event.reportId}: damage=${result.isRoadDamage} ${result.damageType} - ${result.description}")
                return@withContext result
            } finally {
                conn.disconnect()
            }
        }
        error(lastError.ifBlank { "Gemini did not answer" })
    }

    /** Checks a newly logged pothole in the background (if a provider is set up and auto is on). */
    fun verifyInBackground(context: Context, saved: PotholeJson.Saved) {
        val app = context.applicationContext
        if (!autoVerify(app) || !isConfigured(app)) return
        backgroundScope.launch {
            try {
                verify(app, saved)
            } catch (e: Exception) {
                Log.w(TAG, "background check of ${saved.event.reportId} failed (kept for later): ${e.message}")
            }
        }
    }

    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- Ollama (own PC / server)

    private suspend fun verifyOllama(context: Context, saved: PotholeJson.Saved): PotholeJson.VisionResult = withContext(Dispatchers.IO) {
        val photo = saved.frame ?: saved.strip ?: error("no photo saved for this pothole")
        val server = serverUrl(context)
        val model = model(context)
        val requestId = UUID.randomUUID().toString()

        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("format", schema())
            put("options", JSONObject().put("temperature", 0))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", PROMPT)
                put("images", JSONArray().put(encode(photo.path)))
            }))
        }

        val conn = (URL("$server/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 180_000          // CPU-only servers can take a minute per photo
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error("server answered ${conn.responseCode}: ${err.take(200)}")
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            val answer = JSONObject(reply.getJSONObject("message").getString("content"))
            val result = PotholeJson.VisionResult(
                isRoadDamage = answer.optBoolean("is_road_damage"),
                damageType = answer.optString("damage_type", "none"),
                size = answer.optString("size", "none"),
                description = answer.optString("description"),
                confidence = answer.optDouble("confidence", 0.0).toFloat(),
                provider = "Local: $model (Ollama)",
                promptVersion = PROMPT_VERSION,
                requestId = requestId,
                checkedAtMs = System.currentTimeMillis()
            )
            PotholeJson.saveVision(saved.folder, saved.event.id, result)
            Log.i(TAG, "pothole ${saved.event.reportId}: damage=${result.isRoadDamage} ${result.damageType} - ${result.description}")
            result
        } finally {
            conn.disconnect()
        }
    }

    /** JPEG, longest side <= 1024 px, as base64 (smaller upload, enough detail for the model). */
    private fun encode(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("could not read the photo")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
