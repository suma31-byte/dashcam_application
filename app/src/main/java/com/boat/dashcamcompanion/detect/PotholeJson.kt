package com.boat.dashcamcompanion.detect

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** potholes.json <-> PotholeEvent, and reading every saved pothole for the list/detail screens. */
object PotholeJson {

    fun toJson(e: PotholeEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("reportId", e.reportId)
        put("firstSeenMs", e.firstSeenMs)
        put("lastSeenMs", e.lastSeenMs)
        e.lat?.let { put("lat", it) }
        e.lon?.let { put("lon", it) }
        e.accuracyM?.let { put("accuracyM", it.toDouble()) }
        e.bearingDeg?.let { put("bearingDeg", it.toDouble()) }
        e.speedMps?.let { put("speedMps", it.toDouble()) }
        put("locationSource", e.locationSource)
        e.fixAgeMs?.let { put("fixAgeMs", it) }
        e.place?.let { put("place", it) }
        put("seenCount", e.seenCount)
        put("maxConfidence", e.maxConfidence.toDouble())
        put("sizeClass", e.sizeClass)
        put("imageQuality", e.imageQuality)
        e.damageType?.let { put("damageType", it) }
        e.assessment?.let { put("assessment", it) }
        e.description?.let { put("description", it) }
        e.snapshotFile?.let { put("snapshotFile", it) }
        e.frameFile?.let { put("frameFile", it) }
        put("captureSource", e.captureSource)
        e.videoOffsetMs?.let { put("videoOffsetMs", it) }
        e.driveId?.let { put("driveId", it) }
        put("model", e.model)
    }

    fun fromJson(o: JSONObject): PotholeEvent = PotholeEvent(
        id = o.getInt("id"),
        reportId = o.optString("reportId"),
        firstSeenMs = o.getLong("firstSeenMs"),
        lastSeenMs = o.optLong("lastSeenMs"),
        lat = o.optDoubleOrNull("lat"),
        lon = o.optDoubleOrNull("lon"),
        accuracyM = o.optDoubleOrNull("accuracyM")?.toFloat(),
        bearingDeg = o.optDoubleOrNull("bearingDeg")?.toFloat(),
        speedMps = o.optDoubleOrNull("speedMps")?.toFloat(),
        locationSource = o.optString("locationSource", "phone GPS"),
        fixAgeMs = if (o.has("fixAgeMs")) o.getLong("fixAgeMs") else null,
        place = o.optStringOrNull("place"),
        seenCount = o.optInt("seenCount", 1),
        maxConfidence = o.optDouble("maxConfidence", 0.0).toFloat(),
        sizeClass = o.optString("sizeClass", "unknown"),
        imageQuality = o.optString("imageQuality", "unknown"),
        damageType = o.optStringOrNull("damageType"),
        assessment = o.optStringOrNull("assessment"),
        description = o.optStringOrNull("description"),
        snapshotFile = o.optStringOrNull("snapshotFile"),
        frameFile = o.optStringOrNull("frameFile"),
        captureSource = o.optString("captureSource", "unknown"),
        videoOffsetMs = if (o.has("videoOffsetMs")) o.getLong("videoOffsetMs") else null,
        driveId = o.optStringOrNull("driveId"),
        model = o.optString("model", "YOLO11n")
    )

    /** A saved pothole plus the folder its photos are in. */
    data class Saved(val event: PotholeEvent, val folder: File) {
        val frame: File? get() = event.frameFile?.let { File(folder, it) }?.takeIf { it.exists() }
        val strip: File? get() = event.snapshotFile?.let { File(folder, it) }?.takeIf { it.exists() }
        val photo: File? get() = frame ?: strip
    }

    // ---------------------------------------------------------------- vision results
    // Stored separately (vision.json next to potholes.json), because the pothole log rewrites
    // potholes.json while a session is still recording.

    data class VisionResult(
        val isRoadDamage: Boolean,
        val damageType: String,
        val size: String,
        val description: String,
        val confidence: Float,
        val provider: String,
        val promptVersion: String,
        val requestId: String,
        val checkedAtMs: Long
    )

    @Synchronized
    fun saveVision(folder: File, id: Int, r: VisionResult) {
        val f = File(folder, "vision.json")
        val all = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        all.put(id.toString(), JSONObject().apply {
            put("isRoadDamage", r.isRoadDamage)
            put("damageType", r.damageType)
            put("size", r.size)
            put("description", r.description)
            put("confidence", r.confidence.toDouble())
            put("provider", r.provider)
            put("promptVersion", r.promptVersion)
            put("requestId", r.requestId)
            put("checkedAtMs", r.checkedAtMs)
        })
        f.writeText(all.toString(2))
    }

    /** A known pothole seen again on a later drive (stored next to its own potholes.json). */
    @Synchronized
    fun recordRevisit(folder: File, id: Int, seenMs: Long, confidence: Float) {
        val f = File(folder, "revisits.json")
        val all = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        val r = all.optJSONObject(id.toString()) ?: JSONObject().put("drives", 0)
        r.put("drives", r.optInt("drives") + 1)
        r.put("lastSeenMs", seenMs)
        r.put("lastConfidence", confidence.toDouble())
        all.put(id.toString(), r)
        f.writeText(all.toString(2))
    }

    /** Deletes every saved pothole (all sessions). Trips are kept. */
    fun deleteAll(context: Context): Int {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "potholes") } ?: return 0
        val n = root.listFiles()?.count { it.isDirectory } ?: 0
        root.deleteRecursively()
        return n
    }

    private fun applyRevisits(e: PotholeEvent, r: JSONObject?): PotholeEvent =
        if (r == null) e else e.copy(drivesSeen = 1 + r.optInt("drives"), lastDriveSeenMs = r.optLong("lastSeenMs"))

    private fun applyVision(e: PotholeEvent, v: JSONObject?): PotholeEvent {
        if (v == null) return e
        val damaged = v.optBoolean("isRoadDamage")
        return e.copy(
            damageType = v.optString("damageType"),
            assessment = if (damaged) "damaged" else "undamaged (not road damage)",
            description = v.optString("description"),
            visionProvider = v.optString("provider"),
            promptVersion = v.optString("promptVersion"),
            visionRequestId = v.optString("requestId"),
            visionCheckedAtMs = v.optLong("checkedAtMs"),
            visionConfidence = v.optDouble("confidence", 0.0).toFloat()
        )
    }

    /** Every pothole from every session, newest first. */
    fun loadAll(context: Context): List<Saved> {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "potholes") }
            ?: return emptyList()
        val out = ArrayList<Saved>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val f = File(dir, "potholes.json")
            if (!f.exists()) return@forEach
            try {
                val vf = File(dir, "vision.json")
                val vision = try { if (vf.exists()) JSONObject(vf.readText()) else null } catch (_: Exception) { null }
                val rf = File(dir, "revisits.json")
                val revisits = try { if (rf.exists()) JSONObject(rf.readText()) else null } catch (_: Exception) { null }
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val e = fromJson(arr.getJSONObject(i))
                    val withVision = applyVision(e, vision?.optJSONObject(e.id.toString()))
                    out += Saved(applyRevisits(withVision, revisits?.optJSONObject(e.id.toString())), dir)
                }
            } catch (_: Exception) {
                // a half-written file from a crash: skip it
            }
        }
        return out.sortedByDescending { it.event.firstSeenMs }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
