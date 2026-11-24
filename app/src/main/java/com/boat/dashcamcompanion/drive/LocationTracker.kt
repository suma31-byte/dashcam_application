package com.boat.dashcamcompanion.drive

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Current GPS state for the screen and for other components. */
data class GpsState(
    val location: Location? = null,
    val status: String = "off",
    val fixCount: Long = 0
) {
    val speedKmh: Float? get() = location?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
    val accuracyM: Float? get() = location?.takeIf { it.hasAccuracy() }?.accuracy
}

/**
 * One shared GPS source for the whole app (live view, trip log, pothole log).
 *
 * Uses the phone's own GPS (works offline, so it is fine while the phone is on the dashcam's
 * Wi-Fi). Keeps the last minute of fixes so a detection can be matched to the fix closest in
 * time - the idea of NativeGpsFixHistory in Gaurav Sen's pothole-reporter (MIT License).
 *
 * start()/stop() are reference-counted: GPS runs while at least one user needs it.
 */
object LocationTracker : LocationListener {
    private const val TAG = "LocationTracker"
    private const val HISTORY_MS = 60_000L
    private const val MAX_FIX_AGE_MS = 5_000L

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private val listeners = mutableListOf<(Location) -> Unit>()
    private val history = ArrayDeque<Pair<Long, Location>>()   // (wall-clock receive time, fix)
    private var users = 0
    private var manager: LocationManager? = null

    @SuppressLint("MissingPermission") // checked below
    @Synchronized
    fun start(context: Context) {
        users++
        if (users > 1) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val status = when {
            !granted -> "no Location permission"
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> "GPS is switched off"
            else -> try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                "waiting for fix"
            } catch (e: SecurityException) {
                "no Location permission"
            }
        }
        _state.value = GpsState(status = status)
        Log.i(TAG, "GPS start: $status")
    }

    @Synchronized
    fun stop() {
        if (users == 0) return
        users--
        if (users > 0) return
        try { manager?.removeUpdates(this) } catch (_: SecurityException) {}
        history.clear()
        _state.value = GpsState(status = "off")
        Log.i(TAG, "GPS stopped")
    }

    @Synchronized
    fun addListener(listener: (Location) -> Unit) { listeners += listener }

    @Synchronized
    fun removeListener(listener: (Location) -> Unit) { listeners -= listener }

    /** Latest fix, or null if there is none from the last few seconds. */
    @Synchronized
    fun latest(): Location? {
        val last = history.lastOrNull() ?: return null
        return if (System.currentTimeMillis() - last.first <= MAX_FIX_AGE_MS) last.second else null
    }

    /** Like [fixNear], plus how old the fix was at [wallMs] in ms (negative = fix arrived just after). */
    @Synchronized
    fun fixNearWithAge(wallMs: Long, maxGapMs: Long = 3_000L): Pair<Location, Long>? =
        history.minByOrNull { abs(it.first - wallMs) }
            ?.takeIf { abs(it.first - wallMs) <= maxGapMs }
            ?.let { it.second to (wallMs - it.first) }

    /** The fix received closest in time to [wallMs] (within [maxGapMs]), e.g. a detection's time. */
    @Synchronized
    fun fixNear(wallMs: Long, maxGapMs: Long = 3_000L): Location? =
        history.minByOrNull { abs(it.first - wallMs) }
            ?.takeIf { abs(it.first - wallMs) <= maxGapMs }?.second

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val toNotify: List<(Location) -> Unit>
        synchronized(this) {
            history.addLast(now to location)
            while (history.isNotEmpty() && now - history.first().first > HISTORY_MS) history.removeFirst()
            val count = _state.value.fixCount + 1
            val acc = if (location.hasAccuracy()) " ±${location.accuracy.toInt()} m" else ""
            _state.value = GpsState(location, "fix$acc", count)
            toNotify = listeners.toList()
        }
        toNotify.forEach { it(location) }
    }

    override fun onProviderDisabled(provider: String) {
        _state.value = _state.value.copy(status = "GPS is switched off")
    }

    override fun onProviderEnabled(provider: String) {
        _state.value = _state.value.copy(status = "waiting for fix")
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
