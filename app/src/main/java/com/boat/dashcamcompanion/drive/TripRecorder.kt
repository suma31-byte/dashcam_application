package com.boat.dashcamcompanion.drive

import android.content.Context
import android.location.Location
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live numbers of the current trip, for the screen and the notification. */
data class TripStats(
    val active: Boolean = false,
    val startMs: Long = 0L,
    val distanceM: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val potholes: Int = 0,
    val points: Int = 0,
    val folder: String? = null
) {
    val durationS: Long get() = if (startMs == 0L) 0 else (System.currentTimeMillis() - startMs) / 1000
    val avgSpeedKmh: Float get() = if (durationS > 0) (distanceM / durationS * 3.6).toFloat() else 0f
}

/**
 * Records one drive ("trip"): route, distance, duration, speeds and potholes.
 *
 * Saved to Android/data/com.boat.dashcamcompanion/files/Documents/trips/<yyyyMMdd_HHmmss>/
 *   route.csv   time, lat, lon, speed (km/h), accuracy (m), bearing - one row per accepted GPS fix
 *   route.gpx   the same route, opens in Google Earth / map tools
 *   trip.json   summary (rewritten every 30 s and at the end, so a crash keeps most of it)
 */
object TripRecorder {
    private const val TAG = "TripRecorder"
    private const val MIN_STEP_M = 5f          // ignore GPS jitter smaller than this
    private const val MAX_ACCURACY_M = 30f     // ignore poor fixes
    private const val SUMMARY_EVERY_MS = 30_000L

    private val _stats = MutableStateFlow(TripStats())
    val stats: StateFlow<TripStats> = _stats

    /** Route points (lat, lon) of the current trip, for the map. */
    private val _route = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val routePoints: StateFlow<List<Pair<Double, Double>>> = _route

    private val lock = Any()
    private var dir: File? = null
    private var csv: BufferedWriter? = null
    private var lastPoint: Location? = null
    private val route = mutableListOf<Location>()
    private var lastSummaryMs = 0L
    private var appContext: Context? = null
    private val listener: (Location) -> Unit = { onFix(it) }

    val isActive: Boolean get() = _stats.value.active

    fun start(context: Context) {
        synchronized(lock) {
            if (_stats.value.active) return
            appContext = context.applicationContext
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            dir = base?.let { File(it, "trips/$name").apply { mkdirs() } }
            csv = dir?.let { d ->
                File(d, "route.csv").bufferedWriter().also {
                    it.appendLine("time,lat,lon,speed_kmh,accuracy_m,bearing_deg")
                }
            }
            route.clear()
            _route.value = emptyList()
            lastPoint = null
            lastSummaryMs = System.currentTimeMillis()
            _stats.value = TripStats(active = true, startMs = System.currentTimeMillis(), folder = dir?.absolutePath)
        }
        LocationTracker.start(context)
        LocationTracker.addListener(listener)
        AdvanceWarnings.start(context)
        Log.i(TAG, "trip started: ${dir?.absolutePath}")
    }

    fun stop() {
        synchronized(lock) {
            if (!_stats.value.active) return
        }
        AdvanceWarnings.stop()
        LocationTracker.removeListener(listener)
        LocationTracker.stop()
        synchronized(lock) {
            writeSummary()
            writeGpx()
            try { csv?.close() } catch (_: Exception) {}
            csv = null
            val s = _stats.value
            Log.i(TAG, "trip ended: %.2f km, %d s, %d potholes".format(s.distanceM / 1000, s.durationS, s.potholes))
            _stats.value = s.copy(active = false)
        }
    }

    /** Route of the most recent saved trip (route.csv), e.g. to show on the map after a drive. */
    fun loadLatestRoute(context: Context): List<Pair<Double, Double>> {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "trips") } ?: return emptyList()
        val latest = root.listFiles()?.filter { File(it, "route.csv").exists() }?.maxByOrNull { it.name } ?: return emptyList()
        return try {
            File(latest, "route.csv").readLines().drop(1).mapNotNull { line ->
                val p = line.split(",")
                if (p.size >= 3) (p[1].toDoubleOrNull() ?: return@mapNotNull null) to (p[2].toDoubleOrNull() ?: return@mapNotNull null) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Called by the pothole log when a NEW pothole is logged. */
    fun onPothole() {
        synchronized(lock) {
            if (_stats.value.active) _stats.value = _stats.value.copy(potholes = _stats.value.potholes + 1)
        }
    }

    private fun onFix(loc: Location) {
        synchronized(lock) {
            if (!_stats.value.active) return
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
            val prev = lastPoint
            val step = prev?.distanceTo(loc) ?: 0f
            if (prev != null && step < MIN_STEP_M) return
            lastPoint = loc
            route += loc
            _route.value = _route.value + (loc.latitude to loc.longitude)
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            try {
                csv?.appendLine(
                    listOf(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(loc.time)),
                        "%.7f".format(Locale.US, loc.latitude), "%.7f".format(Locale.US, loc.longitude),
                        "%.1f".format(Locale.US, speedKmh),
                        if (loc.hasAccuracy()) "%.1f".format(Locale.US, loc.accuracy) else "",
                        if (loc.hasBearing()) "%.0f".format(Locale.US, loc.bearing) else ""
                    ).joinToString(",")
                )
                csv?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "route.csv write failed", e)
            }
            val s = _stats.value
            _stats.value = s.copy(
                distanceM = s.distanceM + step,
                maxSpeedKmh = maxOf(s.maxSpeedKmh, speedKmh),
                points = s.points + 1
            )
            val now = System.currentTimeMillis()
            if (now - lastSummaryMs >= SUMMARY_EVERY_MS) {
                lastSummaryMs = now
                writeSummary()
            }
        }
    }

    private fun writeSummary() {
        val d = dir ?: return
        val s = _stats.value
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val json = """
            {
              "start": "${iso.format(Date(s.startMs))}",
              "end": "${iso.format(Date())}",
              "duration_s": ${s.durationS},
              "distance_km": ${"%.3f".format(Locale.US, s.distanceM / 1000)},
              "avg_speed_kmh": ${"%.1f".format(Locale.US, s.avgSpeedKmh)},
              "max_speed_kmh": ${"%.1f".format(Locale.US, s.maxSpeedKmh)},
              "potholes": ${s.potholes},
              "route_points": ${s.points}
            }
        """.trimIndent()
        try { File(d, "trip.json").writeText(json) } catch (e: Exception) { Log.w(TAG, "trip.json write failed", e) }
    }

    private fun writeGpx() {
        val d = dir ?: return
        if (route.isEmpty()) return
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        try {
            File(d, "route.gpx").bufferedWriter().use { out ->
                out.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                out.appendLine("""<gpx version="1.1" creator="DashcamCompanion" xmlns="http://www.topografix.com/GPX/1/1">""")
                out.appendLine("<trk><name>${d.name}</name><trkseg>")
                for (p in route) {
                    out.appendLine("""<trkpt lat="${"%.7f".format(Locale.US, p.latitude)}" lon="${"%.7f".format(Locale.US, p.longitude)}"><time>${iso.format(Date(p.time))}</time></trkpt>""")
                }
                out.appendLine("</trkseg></trk></gpx>")
            }
        } catch (e: Exception) {
            Log.w(TAG, "route.gpx write failed", e)
        }
    }
}
