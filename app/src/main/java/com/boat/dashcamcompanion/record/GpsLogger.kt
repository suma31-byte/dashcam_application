package com.boat.dashcamcompanion.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/**
 * Writes the phone's GPS position to a CSV about once a second while a recording is running.
 * The phone's own GPS is used (it works without internet, so it is fine while the phone is on the
 * dashcam's Wi-Fi).
 */
class GpsLogger(private val context: Context) : LocationListener {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val lock = Any()
    private var writer: BufferedWriter? = null

    @Volatile var fixCount = 0L
        private set

    /** Short human-readable state for the screen, e.g. "waiting for fix" or "fix ±4 m". */
    @Volatile var status = "not started"
        private set

    @SuppressLint("MissingPermission") // checked just below
    fun start(file: File) {
        fixCount = 0
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            status = "no Location permission"
            return
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            status = "GPS is switched off"
            return
        }
        try {
            synchronized(lock) {
                writer = file.bufferedWriter().also {
                    it.appendLine(RecordFormat.GPS_HEADER)
                    it.flush()
                }
            }
            status = "waiting for fix"
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: IOException) {
            Log.w(TAG, "could not open GPS log", e)
            status = "could not write GPS log"
        } catch (e: SecurityException) {
            status = "no Location permission"
        }
    }

    fun stop() {
        try {
            manager.removeUpdates(this)
        } catch (e: SecurityException) {
            // permission revoked mid-recording; nothing left to remove
        }
        synchronized(lock) {
            try {
                writer?.close()
            } catch (e: IOException) {
                Log.w(TAG, "closing GPS log failed", e)
            }
            writer = null
        }
    }

    override fun onLocationChanged(location: Location) {
        synchronized(lock) {
            val out = writer ?: return
            try {
                out.appendLine(
                    RecordFormat.gpsLine(
                        wallMs = System.currentTimeMillis(),
                        gpsTimeMs = location.time,
                        lat = location.latitude,
                        lon = location.longitude,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        bearingDeg = if (location.hasBearing()) location.bearing else null,
                        accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                        altitudeM = if (location.hasAltitude()) location.altitude else null
                    )
                )
                out.flush() // one line a second; flushing keeps the file usable if the app is killed
                fixCount++
                status = if (location.hasAccuracy()) "fix ±${location.accuracy.toInt()} m" else "fix"
            } catch (e: IOException) {
                Log.w(TAG, "writing GPS fix failed", e)
            }
        }
    }

    // Older Android versions still call these, and they are not optional there.
    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        status = "GPS is switched off"
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private companion object {
        const val TAG = "GpsLogger"
    }
}
