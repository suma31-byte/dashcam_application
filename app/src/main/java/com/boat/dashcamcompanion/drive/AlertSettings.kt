package com.boat.dashcamcompanion.drive

import android.content.Context

/** Pothole alert switches (Pothole Safety screen). Voice on/off lives in VoiceAlerts. */
object AlertSettings {
    private const val PREFS = "pothole_alerts"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch for all pothole alerts (voice, advance warning, notifications). */
    fun alertsOn(c: Context) = p(c).getBoolean("all", true)
    fun advanceOn(c: Context) = alertsOn(c) && p(c).getBoolean("advance", true)
    fun notificationsOn(c: Context) = alertsOn(c) && p(c).getBoolean("notify", true)
    fun advanceSetting(c: Context) = p(c).getBoolean("advance", true)
    fun notificationsSetting(c: Context) = p(c).getBoolean("notify", true)

    fun save(c: Context, all: Boolean, advance: Boolean, notify: Boolean) {
        p(c).edit().putBoolean("all", all).putBoolean("advance", advance).putBoolean("notify", notify).apply()
    }
}
