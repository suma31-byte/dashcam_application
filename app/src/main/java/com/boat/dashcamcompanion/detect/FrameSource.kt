package com.boat.dashcamcompanion.detect

import android.graphics.Bitmap

/** Anything that can hand the detection loop video frames (live decoder, video file, …). */
interface FrameSource {
    /** Short name for logs, e.g. "file". */
    val name: String
    fun setEnabled(on: Boolean)
    /** Road strip (below RoadBand.TOP_FRACTION) of the current frame, or null if none yet. */
    suspend fun captureRoadStrip(): Bitmap?
    /** Whole current frame (new bitmap, caller owns it), or null. */
    suspend fun captureFullFrame(): Bitmap?
}
