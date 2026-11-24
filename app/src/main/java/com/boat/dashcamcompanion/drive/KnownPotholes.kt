package com.boat.dashcamcompanion.drive

import android.content.Context
import android.location.Location
import android.util.Log
import com.boat.dashcamcompanion.detect.PotholeJson
import java.io.File
import kotlin.math.abs

/**
 * Potholes saved on EARLIER drives (all sessions on this phone), so that:
 *  - a pothole seen again on a later drive updates its existing record ("seen on 5 drives")
 *    instead of being saved again (PotholeEventLog), and
 *  - advance warnings know where potholes are (AdvanceWarnings).
 * Same-pothole rule (from pothole-reporter's de-duplication): within 12 m when both directions
 * of travel are known and differ by < 45°, otherwise within 5 m.
 */
object KnownPotholes {
    private const val TAG = "KnownPotholes"
    private const val RADIUS_M = 12f
    private const val RADIUS_NO_HEADING_M = 5f
    private const val MAX_HEADING_DIFF = 45f

    data class Known(
        val reportId: String,
        val folder: File,
        val eventId: Int,
        val lat: Double,
        val lon: Double,
        val heading: Float?,
        val loggedMs: Long,
        val rejected: Boolean
    )

    @Volatile private var items: List<Known> = emptyList()

    /** Reloads from the saved files (call from a background thread). */
    fun reload(context: Context) {
        items = try {
            PotholeJson.loadAll(context).mapNotNull { s ->
                val e = s.event
                if (e.lat == null || e.lon == null) null
                else Known(e.reportId, s.folder, e.id, e.lat, e.lon, e.bearingDeg, e.firstSeenMs,
                    rejected = e.assessment?.startsWith("undamaged") == true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not load saved potholes", e)
            emptyList()
        }
        Log.i(TAG, "${items.size} known potholes loaded")
    }

    fun all(): List<Known> = items

    fun clear() { items = emptyList() }

    /** A pothole saved on an earlier drive at this spot (ignoring [excludeFolder] = this drive). */
    fun findSame(loc: Location, excludeFolder: File?): Known? {
        val dist = FloatArray(1)
        var best: Known? = null
        var bestD = Float.MAX_VALUE
        for (k in items) {
            if (k.folder == excludeFolder) continue
            Location.distanceBetween(loc.latitude, loc.longitude, k.lat, k.lon, dist)
            val headingKnown = loc.hasBearing() && k.heading != null
            val radius = if (headingKnown) RADIUS_M else RADIUS_NO_HEADING_M
            if (dist[0] > radius) continue
            if (headingKnown && angleDiff(loc.bearing, k.heading!!) > MAX_HEADING_DIFF) continue
            if (dist[0] < bestD) { bestD = dist[0]; best = k }
        }
        return best
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return if (d > 180f) 360f - d else d
    }
}
