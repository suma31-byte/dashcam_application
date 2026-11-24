package com.boat.dashcamcompanion.stream

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * An off-screen Surface that accepts the live video while the screen is off and immediately
 * discards each frame. Lets the live-view decoder keep running in the background (pothole
 * detection uses its own decoder, see AnalysisFrameDecoder).
 */
class BackgroundSurfaceSink(width: Int = 960, height: Int = 544) {
    private val thread = HandlerThread("BackgroundSurfaceSink").also { it.start() }
    private val reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 4).apply {
        setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, Handler(thread.looper))
    }

    val surface: Surface get() = reader.surface

    fun release() {
        try { reader.close() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
