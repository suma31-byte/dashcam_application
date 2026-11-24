package com.boat.dashcamcompanion.detect

/**
 * A detected pothole. Coordinates are fractions (0..1) of the image the detector was given,
 * so they stay valid at any display size.
 */
data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float
)

/**
 * Area of the frame the detector looks at - where the road ahead is.
 *  - Dashcam / landscape video: the bottom strip (rows 600..1080 of a 1080-row frame).
 *  - Portrait phone video (filmed from a bike or by hand): the road ahead is higher up and the
 *    bottom shows the vehicle's front, so the band is 25%..88% of the height.
 * The detector only ever sees this band; its boxes are mapped back to the full frame here.
 */
object RoadBand {
    const val TOP_FRACTION = 600f / 1080f          // dashcam default

    @Volatile var top: Float = TOP_FRACTION
        private set
    @Volatile var bottom: Float = 1f
        private set

    /** Height / width of the current video (0 = unknown, e.g. live stream). */
    @Volatile var videoAspect: Float = 0f
        private set

    /** Chooses the band for a video of this size (portrait = phone video). */
    fun setForVideo(width: Int, height: Int) {
        videoAspect = if (width > 0 && height > 0) height.toFloat() / width else 0f
        if (width > 0 && height > width) { top = 0.25f; bottom = 0.88f } else reset()
    }

    /** Dashcam band (live view). */
    fun reset() { top = TOP_FRACTION; bottom = 1f; videoAspect = 0f }

    val isPortraitBand: Boolean get() = top != TOP_FRACTION || bottom != 1f

    /** Converts a box in road-band fractions to full-frame fractions. */
    fun Detection.toFullFrame(): Detection {
        val t = RoadBand.top
        val h = RoadBand.bottom - t
        return copy(top = t + top * h, bottom = t + bottom * h)
    }
}
