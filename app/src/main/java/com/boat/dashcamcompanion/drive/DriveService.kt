package com.boat.dashcamcompanion.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.boat.dashcamcompanion.MainActivity
import com.boat.dashcamcompanion.R

/**
 * Keeps a drive running when the screen is off or another app is in front:
 *  - foreground service (type "location") with a notification, so Android keeps the app alive
 *    and allows GPS in the background,
 *  - partial wake lock, so the CPU keeps decoding / detecting with the screen off,
 *  - high-performance Wi-Fi lock, so the dashcam stream does not stall when Wi-Fi power-saves.
 * The notification shows live trip numbers (distance, time, potholes).
 *
 * Start it while the app is visible (Android requires that for location services).
 * Based on the role of DriveForegroundService in Gaurav Sen's pothole-reporter (MIT License).
 */
class DriveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. started from the background on Android 12+, or location permission missing
            Log.w(TAG, "could not start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        handler.removeCallbacks(updater)
        handler.postDelayed(updater, 5_000L)
        Log.i(TAG, "drive service running")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        releaseLocks()
        Log.i(TAG, "drive service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashcamCompanion:drive").apply {
                setReferenceCounted(false)
                acquire(MAX_DRIVE_MS)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "DashcamCompanion:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val s = TripRecorder.stats.value
        val gps = LocationTracker.state.value
        val minutes = s.durationS / 60
        val text = "%.1f km · %d min · %d potholes · GPS: %s".format(s.distanceM / 1000, minutes, s.potholes, gps.status)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pothole detection running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Drive", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a drive with pothole detection is running"
                }
            )
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "DriveService"
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 42
        private const val MAX_DRIVE_MS = 4 * 60 * 60 * 1000L   // wake lock safety limit: 4 hours

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start drive service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
