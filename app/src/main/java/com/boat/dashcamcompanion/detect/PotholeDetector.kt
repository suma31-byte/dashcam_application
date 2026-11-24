package com.boat.dashcamcompanion.detect

import android.graphics.Bitmap
import android.os.SystemClock

/** Finds potholes in the road strip of one video frame. Implementations run off the main thread. */
interface PotholeDetector {
    /** [roadStrip] is the bottom strip of the frame (see [RoadBand]); boxes are fractions of it. */
    fun detect(roadStrip: Bitmap): List<Detection>

    fun close() {}
}

/**
 * Stand-in used until the real model is plugged in: a single box that sweeps across the road
 * strip every few seconds. If it lines up with the video and moves smoothly, the frame capture,
 * crop and overlay plumbing is correct.
 */
class DummyDetector : PotholeDetector {
    override fun detect(roadStrip: Bitmap): List<Detection> {
        val phase = (SystemClock.elapsedRealtime() % SWEEP_MS).toFloat() / SWEEP_MS
        val left = phase * (1f - BOX_WIDTH)
        return listOf(Detection(left, 0.35f, left + BOX_WIDTH, 0.8f, 0.99f))
    }

    private companion object {
        const val SWEEP_MS = 6000L
        const val BOX_WIDTH = 0.2f
    }
}
