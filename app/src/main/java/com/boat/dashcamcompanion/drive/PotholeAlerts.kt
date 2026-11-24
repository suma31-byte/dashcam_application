package com.boat.dashcamcompanion.drive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.boat.dashcamcompanion.MainActivity
import com.boat.dashcamcompanion.R
import com.boat.dashcamcompanion.detect.PotholeEvent
import java.util.Locale

/**
 * A notification for every NEW pothole (repeat sightings of the same pothole don't alert again).
 * Tapping it opens that pothole's report. Several potholes within a few seconds update one
 * notification instead of flooding the phone. Voice alerts ("Pothole ahead") come later.
 */
object PotholeAlerts {
    private const val CHANNEL_ID = "pothole_alerts"
    private const val NOTIFICATION_ID = 43
    private const val GROUP_WINDOW_MS = 10_000L
    const val EXTRA_REPORT_ID = "open_report_id"

    private var lastAlertMs = 0L
    private var countInWindow = 0

    @Synchronized
    fun onNewPothole(context: Context, e: PotholeEvent) {
        if (!AlertSettings.notificationsOn(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val now = System.currentTimeMillis()
        countInWindow = if (now - lastAlertMs <= GROUP_WINDOW_MS) countInWindow + 1 else 1
        lastAlertMs = now

        val where = e.place ?: if (e.lat != null && e.lon != null)
            "%.6f, %.6f".format(Locale.US, e.lat, e.lon) else "location not available"
        val title = if (countInWindow > 1) "$countInWindow potholes detected" else "Pothole detected"
        val text = "%s · %s · %.0f%%".format(where, e.sizeClass, e.maxConfidence * 100)

        val open = PendingIntent.getActivity(
            context, e.id,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_REPORT_ID, e.reportId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setTimeoutAfter(60_000L)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Pothole alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "A notification for each newly detected pothole"
                    }
                )
            }
        }
    }
}
