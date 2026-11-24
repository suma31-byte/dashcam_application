package com.boat.dashcamcompanion.drive

import android.content.Context
import android.location.Location
import android.util.Log
import com.boat.dashcamcompanion.detect.PotholeEvent
import kotlin.math.abs

/**
 * Advance warning for potholes that are ALREADY KNOWN (logged on earlier drives / earlier in this
 * drive): "Pothole in 100 metres" when the vehicle is heading towards one - before the camera
 * can see it.
 *
 * Rules (checked on every GPS fix during a trip):
 *  - vehicle moving (> 7 km/h) with a known heading,
 *  - pothole within [MAX_RANGE_M] and roughly straight ahead (bearing to it within 25° of the
 *    heading), and logged while driving the same direction (within 60°) if its heading is known,
 *  - announced once per approach; it can warn again after the vehicle has passed and moved
 *    away (> 250 m) or after 3 minutes,
 *  - potholes logged in the last 2 minutes are skipped (the vehicle has just passed them).
 * Positions are where the vehicle was when the pothole was seen (it is ~5-15 m beyond that).
 */
object AdvanceWarnings {
    private const val TAG = "AdvanceWarnings"
    private const val MAX_RANGE_M = 150f
    private const val MIN_SPEED_MPS = 2f
    private const val AHEAD_DEG = 25f
    private const val SAME_DIRECTION_DEG = 60f
    private const val RESET_DISTANCE_M = 250f
    private const val RESET_TIME_MS = 180_000L
    private const val SKIP_RECENT_MS = 120_000L

    private data class Known(val id: String, val lat: Double, val lon: Double, val heading: Float?, val loggedMs: Long)

    private val known = mutableListOf<Known>()
    private val announced = mutableMapOf<String, Long>()   // id -> time of last warning
    private var appContext: Context? = null
    private var active = false
    private val listener: (Location) -> Unit = { onFix(it) }
    private var lastDiagMs = 0L

    /** Called when a trip starts: loads every saved pothole with a GPS position. */
    fun start(context: Context) {
        synchronized(this) {
            if (active) return
            active = true
            appContext = context.applicationContext
            announced.clear()
        }
        Thread {
            KnownPotholes.reload(context.applicationContext)
            synchronized(this) {
                known.clear()
                // skip potholes the vision model rejected as "not road damage"
                KnownPotholes.all().filter { !it.rejected }.forEach { k ->
                    known += Known(k.reportId, k.lat, k.lon, k.heading, k.loggedMs)
                }
            }
            Log.i(TAG, "advance warnings on: ${known.size} known potholes")
        }.start()
        LocationTracker.addListener(listener)
    }

    fun stop() {
        LocationTracker.removeListener(listener)
        synchronized(this) { active = false }
    }

    /** New pothole logged during this drive: known from now on (warned about on the next pass). */
    fun onLogged(e: PotholeEvent) {
        if (e.lat == null || e.lon == null) return
        synchronized(this) {
            if (known.none { it.id == e.reportId }) known += Known(e.reportId, e.lat, e.lon, e.bearingDeg, e.firstSeenMs)
        }
    }

    private fun onFix(loc: Location) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastDiagMs >= 5_000L) {
            lastDiagMs = now
            diagnose(loc, now)
        }
        if (!loc.hasSpeed() || loc.speed < MIN_SPEED_MPS || !loc.hasBearing()) return
        var nearest: Pair<Known, Float>? = null
        synchronized(this) {
            if (!active) return
            val dist = FloatArray(2)
            for (k in known) {
                if (now - k.loggedMs < SKIP_RECENT_MS) continue
                Location.distanceBetween(loc.latitude, loc.longitude, k.lat, k.lon, dist)
                val d = dist[0]
                val last = announced[k.id]
                if (last != null && (d > RESET_DISTANCE_M || now - last > RESET_TIME_MS)) announced.remove(k.id)
                if (d > MAX_RANGE_M || announced.containsKey(k.id)) continue
                val bearingTo = dist[1]                       // initial bearing car -> pothole
                if (angleDiff(bearingTo, loc.bearing) > AHEAD_DEG) continue
                if (k.heading != null && angleDiff(k.heading, loc.bearing) > SAME_DIRECTION_DEG) continue
                if (nearest == null || d < nearest!!.second) nearest = k to d
            }
            nearest?.let { announced[it.first.id] = now }
        }
        nearest?.let { (k, d) ->
            val metres = when {
                d >= 95 -> ((d / 50).toInt() * 50).coerceAtLeast(100)
                else -> ((d / 10).toInt() * 10).coerceAtLeast(20)
            }
            Log.i(TAG, "warning: pothole ${k.id} in ${d.toInt()} m")
            VoiceAlerts.sayAdvance(ctx, metres)
        }
    }

    /** Every 5 s: why the nearest known pothole is (or isn't) being warned about - for testing. */
    private fun diagnose(loc: Location, now: Long) {
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else -1f
        val heading = if (loc.hasBearing()) loc.bearing else null
        var best: Known? = null
        var bestD = Float.MAX_VALUE
        var bestBearing = 0f
        val dist = FloatArray(2)
        val count: Int
        synchronized(this) {
            count = known.size
            for (k in known) {
                Location.distanceBetween(loc.latitude, loc.longitude, k.lat, k.lon, dist)
                if (dist[0] < bestD) { bestD = dist[0]; best = k; bestBearing = dist[1] }
            }
        }
        val b = best
        val reason = when {
            count == 0 -> "no saved potholes with GPS"
            speedKmh < 0 -> "GPS has no speed"
            speedKmh < MIN_SPEED_MPS * 3.6f -> "too slow (%.0f km/h)".format(speedKmh)
            heading == null -> "GPS has no direction of travel"
            b == null -> "-"
            bestD > MAX_RANGE_M -> "nearest is %.0f m away (warns within %.0f m)".format(bestD, MAX_RANGE_M)
            now - b.loggedMs < SKIP_RECENT_MS -> "nearest was logged < 2 min ago (skipped)"
            angleDiff(bestBearing, heading) > AHEAD_DEG -> "nearest is not ahead (%.0f° off)".format(angleDiff(bestBearing, heading))
            b.heading != null && angleDiff(b.heading, heading) > SAME_DIRECTION_DEG -> "nearest was logged driving another direction"
            announced.containsKey(b.id) -> "already warned on this approach"
            else -> "should warn now"
        }
        Log.d(TAG, "check: $count known · speed %.0f km/h · heading ${heading?.toInt() ?: "-"} · nearest %.0f m → $reason"
            .format(speedKmh, if (bestD == Float.MAX_VALUE) -1f else bestD))
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return if (d > 180f) 360f - d else d
    }
}
