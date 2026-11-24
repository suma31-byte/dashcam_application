package com.boat.dashcamcompanion.record

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Text formats written next to a recording. Kept free of Android classes so they can be unit
 * tested. All numbers use Locale.US so a phone set to a comma-decimal language still writes
 * parseable CSV.
 */
object RecordFormat {

    const val FRAMES_HEADER = "frame,wall_ms,rtp_timestamp,bytes"
    const val GPS_HEADER = "wall_ms,gps_time_ms,lat,lon,speed_mps,bearing_deg,accuracy_m,altitude_m"

    /** One row per video frame: when it arrived on the phone and how big it is. */
    fun frameLine(index: Long, wallMs: Long, rtpTimestamp: Long, bytes: Int): String =
        "$index,$wallMs,$rtpTimestamp,$bytes"

    /** One row per GPS fix. Values the phone could not provide (NaN or null) are left empty. */
    fun gpsLine(
        wallMs: Long,
        gpsTimeMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        bearingDeg: Float?,
        accuracyM: Float?,
        altitudeM: Double?
    ): String = listOf(
        wallMs.toString(),
        gpsTimeMs.toString(),
        String.format(Locale.US, "%.7f", lat),
        String.format(Locale.US, "%.7f", lon),
        optional(speedMps?.toDouble(), "%.2f"),
        optional(bearingDeg?.toDouble(), "%.1f"),
        optional(accuracyM?.toDouble(), "%.1f"),
        optional(altitudeM, "%.1f")
    ).joinToString(",")

    fun sessionName(wallMs: Long): String =
        "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(wallMs))

    fun duration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    private fun optional(value: Double?, pattern: String): String =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, pattern, value)
}
