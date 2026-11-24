package com.boat.dashcamcompanion.detect

import android.content.Context

/** Saved detector options shared by the live view and the video test. */
object DetectorSettings {
    private const val PREFS = "detector_settings"

    /**
     * Run the model on the phone's GPU (much faster on most phones, falls back to the CPU
     * automatically if the GPU can't run it). Logcat "Model loaded ... gpu=true/false" shows which.
     */
    fun useGpu(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gpu", true)
    fun setUseGpu(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gpu", on).apply()
}
