/*
 * Sighting deduplication adapted from Gaurav Sen's pothole-reporter
 * (https://github.com/coding-parrot/pothole-reporter, NativeDeduplicationEngine.kt /
 * DuplicateDetector.kt, MIT License, (c) 2026 Gaurav Sen): repeat sightings of the same
 * road damage within a few metres (and in the same direction of travel) are merged into one
 * event instead of being logged again.
 */
package com.boat.dashcamcompanion.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.boat.dashcamcompanion.detect.RoadBand.toFullFrame
import org.json.JSONArray
import com.boat.dashcamcompanion.drive.LocationTracker
import com.boat.dashcamcompanion.drive.AdvanceWarnings
import com.boat.dashcamcompanion.drive.KnownPotholes
import com.boat.dashcamcompanion.drive.PotholeAlerts
import com.boat.dashcamcompanion.drive.VoiceAlerts
import com.boat.dashcamcompanion.drive.TripRecorder
import com.boat.dashcamcompanion.geo.OfflineGeocoder
import android.location.Location
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One physical pothole, possibly seen several times. Fields mirror pothole-reporter's report view. */
data class PotholeEvent(
    val id: Int,
    /** Unique on this phone: "<session>-<id>". */
    val reportId: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    // ---- location
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMps: Float?,
    /** "phone GPS", "phone GPS (simulated)" or "none (bench test)". */
    val locationSource: String,
    /** How old the GPS fix was when the frame was checked, ms. */
    val fixAgeMs: Long?,
    /** "road, area, city" from the offline OpenStreetMap database, if available. */
    val place: String? = null,
    // ---- detection
    val seenCount: Int,
    /** Number of drives on which this pothole was seen (1 = only the drive it was found on). */
    val drivesSeen: Int = 1,
    /** When it was last seen on a later drive (null = never seen again). */
    val lastDriveSeenMs: Long? = null,
    val maxConfidence: Float,
    /** Rough size from the box area: small / medium / large (approximate - no depth or distance). */
    val sizeClass: String,
    /** From the frame quality check, e.g. "good (sharpness 38)". */
    val imageQuality: String,
    /** Filled in later by the local vision model; null until then. */
    val damageType: String? = null,
    val assessment: String? = null,
    val description: String? = null,
    /** Which vision model checked the photo, e.g. "Local: qwen2.5vl:7b (Ollama)"; null = not checked. */
    val visionProvider: String? = null,
    val promptVersion: String? = null,
    val visionRequestId: String? = null,
    val visionCheckedAtMs: Long? = null,
    /** Vision model's own confidence 0..1. */
    val visionConfidence: Float? = null,
    // ---- photos
    /** Road strip the model saw. */
    val snapshotFile: String?,
    /** Whole camera frame with the detected boxes drawn in. */
    val frameFile: String? = null,
    // ---- source
    val captureSource: String,
    /** For imported videos: position in the clip (ms); null for live. */
    val videoOffsetMs: Long?,
    val driveId: String?,
    val model: String
)

/**
 * Logs confirmed potholes with the phone's GPS position and merges repeat sightings.
 *
 * Saved to Android/data/com.boat.dashcamcompanion/files/Documents/potholes/<session>/:
 *  - potholes.csv        one row per unique pothole (rewritten on every change)
 *  - potholes.json       the same with every field (read by the pothole list / detail screens)
 *  - pothole_<id>.jpg    the road strip the model saw
 *  - frame_<id>.jpg      the whole camera frame with the detected boxes drawn in
 */
class PotholeEventLog(
    private val context: Context,
    /**
     * false for bench tests (video file): the phone is not moving, so its GPS position says
     * nothing about where a pothole in the video is. Sightings are then merged by time only.
     */
    private val useGps: Boolean = true
) {
    /** Shown in the report, e.g. "Drive Mode (live dashcam)" or "Imported video". */
    @Volatile var captureSource: String = "Drive Mode (live dashcam)"
    /** Detection model name, e.g. "YOLO11n FP32 (pothole_fp32.tflite)". */
    @Volatile var modelName: String = "YOLO11n"
    /** For imported videos: current position in the clip, ms. */
    @Volatile var videoOffsetMs: (() -> Long?)? = null

    private val lock = Any()
    private val events = mutableListOf<PotholeEvent>()
    private var sessionDir: File? = null

    private val _count = MutableStateFlow(0)
    /** Number of unique potholes logged in this session, for the screen. */
    val count: StateFlow<Int> = _count

    private var gpsStarted = false
    /** Known potholes (earlier drives) already counted on THIS drive: reportId -> last seen. */
    private val knownSeenThisDrive = mutableMapOf<String, Long>()

    @Volatile var gpsStatus = "not started"
        private set

    /**
     * Starts GPS and, the first time, a new session folder. Calling it again on the same log
     * (detection paused and resumed - e.g. app sent to the background and reopened, or the stream
     * reconnected) CONTINUES the same session: earlier potholes and the count are kept.
     * A new log instance (new screen visit / changed settings) starts a new session.
     */
    fun start() {
        synchronized(lock) {
            if (sessionDir == null) {
                events.clear()
                _count.value = 0
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                sessionDir = base?.let {
                    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    File(it, "potholes/$name").apply { mkdirs() }
                }
            } else {
                Log.i(TAG, "pothole log resumed in $sessionDir (${events.size} potholes so far)")
            }
        }
        if (gpsStarted) return
        if (!useGps) {
            gpsStatus = "GPS off (bench test)"
            Log.i(TAG, "pothole log started in $sessionDir, GPS not used")
            return
        }
        // Potholes from earlier drives: a pothole seen again updates its record instead of
        // being saved twice.
        Thread { KnownPotholes.reload(context) }.start()
        // Shared GPS source (also used by the trip log and the live-view read-out).
        LocationTracker.start(context)
        gpsStatus = LocationTracker.state.value.status
        gpsStarted = true
        Log.i(TAG, "pothole log started in $sessionDir, GPS: $gpsStatus")
    }

    fun stop() {
        if (gpsStarted) {
            LocationTracker.stop()
            gpsStarted = false
        }
    }

    /**
     * Called for every frame with at least one confirmed pothole. [strip] is the image the
     * detector saw (boxes are fractions of it); [fullFrame] fetches the whole camera frame and is
     * only called for a NEW pothole.
     */
    suspend fun onConfirmed(
        confirmed: List<Detection>,
        strip: Bitmap,
        quality: FrameQuality? = null,
        fullFrame: suspend () -> Bitmap? = { null },
        /** When the checked picture was taken (a slow detector answers seconds later). */
        capturedAtMs: Long = System.currentTimeMillis()
    ) {
        if (confirmed.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        // GPS position at the moment the PICTURE was taken, not when the answer arrived: with
        // Gemini (1-5 s) at 60 km/h the vehicle has moved on 15-80 m by then.
        val fix = if (useGps) LocationTracker.fixNearWithAge(capturedAtMs) else null
        if (useGps) gpsStatus = LocationTracker.state.value.status
        val location = fix?.first

        val newIds = ArrayList<Pair<Int, Detection>>()
        synchronized(lock) {
            // Each box is TRACKED across checks (PotholeTracker, FR-04): a box that continues an
            // existing track is the same pothole; a box with no matching track is a new pothole -
            // so a road full of potholes gives one record per pothole, not one for the stretch.
            tracks.removeAll { nowMs - it.lastSeenMs > TRACK_TTL_MS }
            val used = HashSet<Track>()
            for (det in confirmed.sortedByDescending { it.confidence }) {
                val track = tracks.filter { it !in used }.maxByOrNull { matchScore(it.box, det) }
                    ?.takeIf { matchScore(it.box, det) > 0f }
                if (track != null) {
                    used += track
                    track.box = det
                    track.lastSeenMs = nowMs
                    track.eventId?.let { id ->
                        val i = events.indexOfFirst { it.id == id }
                        if (i >= 0) {
                            val e = events[i]
                            events[i] = e.copy(
                                lastSeenMs = nowMs,
                                seenCount = e.seenCount + 1,
                                maxConfidence = max(e.maxConfidence, det.confidence),
                                lat = if (betterFix(location, e)) location!!.latitude else e.lat,
                                lon = if (betterFix(location, e)) location!!.longitude else e.lon,
                                accuracyM = if (betterFix(location, e)) location!!.accuracy else e.accuracyM
                            )
                        }
                    }
                    continue
                }
                val newTrack = Track(det, nowMs, null)
                tracks += newTrack
                used += newTrack

                // Seen on an earlier drive? Then it's not new: update that record.
                val known = if (location != null) KnownPotholes.findSame(location, sessionDir) else null
                if (known != null) {
                    if (knownSeenThisDrive.put(known.reportId, nowMs) == null) {
                        PotholeJson.recordRevisit(known.folder, known.eventId, nowMs, det.confidence)
                        VoiceAlerts.onPotholeSeen(context, sizeOf(listOf(det)) == "large")
                        Log.i(TAG, "known pothole ${known.reportId} seen again on this drive")
                    }
                    continue
                }

                val newId = events.size + 1
                newTrack.eventId = newId
                val session = sessionDir?.name ?: "session"
                events += PotholeEvent(
                    id = newId,
                    reportId = "$session-$newId",
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs,
                    lat = location?.latitude,
                    lon = location?.longitude,
                    accuracyM = location?.takeIf { it.hasAccuracy() }?.accuracy,
                    bearingDeg = location?.takeIf { it.hasBearing() }?.bearing,
                    speedMps = location?.takeIf { it.hasSpeed() }?.speed,
                    locationSource = when {
                        !useGps -> "none (bench test)"
                        location == null -> "phone GPS (no fix)"
                        isMock(location) -> "phone GPS (simulated)"
                        else -> "phone GPS"
                    },
                    fixAgeMs = fix?.second,
                    place = location?.let { OfflineGeocoder.get(context)?.lookup(it.latitude, it.longitude) },
                    seenCount = 1,
                    maxConfidence = det.confidence,
                    sizeClass = sizeOf(listOf(det)),
                    imageQuality = describe(quality),
                    snapshotFile = saveJpeg(strip, "pothole_$newId.jpg"),
                    captureSource = captureSource,
                    videoOffsetMs = videoOffsetMs?.invoke(),
                    driveId = TripRecorder.stats.value.takeIf { it.active }?.folder?.let { File(it).name },
                    model = modelName
                )
                newIds += newId to det
                _count.value = events.size
                TripRecorder.onPothole()
                PotholeAlerts.onNewPothole(context, events.last())
                VoiceAlerts.onPotholeSeen(context, events.last().sizeClass == "large")
                AdvanceWarnings.onLogged(events.last())
                Log.i(TAG, "new pothole #$newId at ${location?.latitude},${location?.longitude} " +
                    "(${events.last().place ?: "no place name"}) conf %.2f".format(det.confidence))
            }
            writeFiles()
        }
        if (newIds.isEmpty()) return

        // Whole camera frame, fetched outside the lock (it waits for the next frame): one photo per
        // new pothole, with that pothole's box drawn thick and the others thin.
        val frame = (try { fullFrame() } catch (e: Exception) { null }) ?: return
        val names = HashMap<Int, String?>()
        for ((id, det) in newIds) {
            val drawn = frame.copy(Bitmap.Config.ARGB_8888, true)
            drawBoxes(drawn, confirmed, highlight = det)
            names[id] = saveJpeg(drawn, "frame_$id.jpg")
            drawn.recycle()
        }
        frame.recycle()
        val toCheck = ArrayList<PotholeJson.Saved>()
        synchronized(lock) {
            for ((id, name) in names) {
                val i = events.indexOfFirst { it.id == id }
                if (i >= 0) {
                    events[i] = events[i].copy(frameFile = name)
                    sessionDir?.let { dir -> toCheck += PotholeJson.Saved(events[i], dir) }
                }
            }
            writeFiles()
        }
        // Vision model check (Gemini / Ollama) in the background: confirms the pothole, adds damage
        // type and description, and marks false positives. Without internet it is done later.
        toCheck.forEach { com.boat.dashcamcompanion.vision.VisionVerifier.verifyInBackground(context, it) }
    }

    // ---------------------------------------------------------------- tracking (FR-04)

    private class Track(var box: Detection, var lastSeenMs: Long, var eventId: Int?)
    private val tracks = mutableListOf<Track>()

    /**
     * > 0 when [det] continues [prev]: they overlap, or the new box is close by and not higher
     * up (potholes move DOWN the picture as the vehicle approaches). Higher = better match.
     */
    private fun matchScore(prev: Detection, det: Detection): Float {
        val iw = max(0f, kotlin.math.min(prev.right, det.right) - max(prev.left, det.left))
        val ih = max(0f, kotlin.math.min(prev.bottom, det.bottom) - max(prev.top, det.top))
        val inter = iw * ih
        val union = (prev.right - prev.left) * (prev.bottom - prev.top) +
            (det.right - det.left) * (det.bottom - det.top) - inter
        val iou = if (union > 0f) inter / union else 0f
        if (iou >= 0.1f) return 1f + iou
        val pcx = (prev.left + prev.right) / 2f; val pcy = (prev.top + prev.bottom) / 2f
        val dcx = (det.left + det.right) / 2f;  val dcy = (det.top + det.bottom) / 2f
        val size = max(prev.right - prev.left, prev.bottom - prev.top)
        val dist = kotlin.math.hypot((dcx - pcx).toDouble(), (dcy - pcy).toDouble()).toFloat()
        val movedUp = dcy < pcy - 0.08f
        return if (!movedUp && dist <= 0.10f + size) 1f - dist else 0f
    }

    private fun drawBoxes(frame: Bitmap, boxes: List<Detection>, highlight: Detection? = null) {
        val canvas = Canvas(frame)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = (w / 250f).coerceAtLeast(3f)
        }
        val thin = (w / 500f).coerceAtLeast(2f)
        val thick = (w / 200f).coerceAtLeast(4f)
        for (d in boxes) {
            val f = d.toFullFrame()
            paint.strokeWidth = if (highlight == null || d === highlight) thick else thin
            paint.color = if (highlight == null || d === highlight) Color.RED else Color.argb(170, 255, 120, 120)
            canvas.drawRect(f.left * w, f.top * h, f.right * w, f.bottom * h, paint)
        }
    }

    private fun sizeOf(boxes: List<Detection>): String {
        val area = boxes.maxOf { d -> val f = d.toFullFrame(); (f.right - f.left) * (f.bottom - f.top) }
        return when {
            area < 0.004f -> "small"
            area < 0.015f -> "medium"
            else -> "large"
        }
    }

    private fun describe(q: FrameQuality?): String = when {
        q == null -> "unknown"
        q.unusableExposure -> "poor (exposure)"
        q.sharpness >= 30f -> "good (sharpness %.0f)".format(Locale.US, q.sharpness)
        q.sharpness >= 15f -> "fair (sharpness %.0f)".format(Locale.US, q.sharpness)
        else -> "poor (sharpness %.0f)".format(Locale.US, q.sharpness)
    }

    @Suppress("DEPRECATION")
    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else l.isFromMockProvider

    // ---------------------------------------------------------------- dedup rules

    private fun isSamePothole(prior: PotholeEvent, location: Location?, nowMs: Long): Boolean {
        val secondsSince = (nowMs - prior.lastSeenMs) / 1000.0
        // Seen continuously (consecutive checks while approaching it) → same pothole.
        if (secondsSince <= SAME_SIGHTING_S) return true

        val priorLat = prior.lat
        val priorLon = prior.lon
        if (location == null || priorLat == null || priorLon == null) {
            // Without GPS we can only merge by time.
            return secondsSince <= NO_GPS_MERGE_S
        }
        val distance = distanceMeters(location.latitude, location.longitude, priorLat, priorLon)
        val headingKnown = location.hasBearing() && prior.bearingDeg != null
        val radius = if (headingKnown) ADJACENT_RADIUS_M else MISSING_HEADING_RADIUS_M
        if (distance > radius) return false
        if (headingKnown && headingDifference(location.bearing, prior.bearingDeg!!) > MAX_HEADING_DIFF_DEG) {
            return false // opposite side / direction of the road
        }
        return true
    }

    private fun betterFix(location: Location?, e: PotholeEvent): Boolean =
        location != null && location.hasAccuracy() &&
            (e.accuracyM == null || location.accuracy < e.accuracyM)

    // ---------------------------------------------------------------- files

    private fun saveJpeg(bitmap: Bitmap, name: String): String? {
        val dir = sessionDir ?: return null
        return try {
            FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        } catch (e: Exception) {
            Log.w(TAG, "could not save $name", e)
            null
        }
    }

    private fun writeFiles() {
        writeCsv()
        val dir = sessionDir ?: return
        try {
            val arr = JSONArray()
            for (e in events) arr.put(PotholeJson.toJson(e))
            File(dir, "potholes.json").writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "could not write potholes.json", e)
        }
    }

    private fun q(v: String?) = "\"" + (v ?: "").replace("\"", "'") + "\""

    private fun writeCsv() {
        val dir = sessionDir ?: return
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            File(dir, "potholes.csv").bufferedWriter().use { out ->
                out.appendLine("id,first_seen,last_seen,lat,lon,accuracy_m,bearing_deg,speed_mps,seen_count,max_confidence,snapshot,place,size,image_quality,frame,capture_source,video_offset_s,location_source,fix_age_ms,drive_id,model,report_id")
                for (e in events) {
                    out.appendLine(
                        listOf(
                            e.id, time.format(Date(e.firstSeenMs)), time.format(Date(e.lastSeenMs)),
                            e.lat ?: "", e.lon ?: "", e.accuracyM ?: "", e.bearingDeg ?: "", e.speedMps ?: "",
                            e.seenCount, "%.3f".format(Locale.US, e.maxConfidence), e.snapshotFile ?: "",
                            q(e.place), e.sizeClass, q(e.imageQuality), e.frameFile ?: "", q(e.captureSource),
                            e.videoOffsetMs?.let { "%.1f".format(Locale.US, it / 1000.0) } ?: "",
                            q(e.locationSource), e.fixAgeMs ?: "", e.driveId ?: "", q(e.model), e.reportId
                        ).joinToString(",")
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write potholes.csv", e)
        }
    }


    companion object {
        private const val TAG = "PotholeEventLog"

        // Values follow pothole-reporter's NativeDeduplicationEngine where they apply on-device.
        /** A track not seen for this long is closed (the pothole has left the picture). */
        const val TRACK_TTL_MS = 2_500L
        const val ADJACENT_RADIUS_M = 12.0          // DEDUPE_ADJACENT_RADIUS_M
        const val MISSING_HEADING_RADIUS_M = 5.0    // DEDUPE_MISSING_HEADING_RADIUS_M
        const val SAME_SIGHTING_S = 4.0             // DEDUPE_SAME_DRIVE_S
        const val NO_GPS_MERGE_S = 4.0
        const val MAX_HEADING_DIFF_DEG = 45f
        const val MAX_FIX_AGE_MS = 5_000L

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a))
        }

        fun headingDifference(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
