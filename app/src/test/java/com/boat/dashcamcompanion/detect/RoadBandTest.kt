package com.boat.dashcamcompanion.detect

import com.boat.dashcamcompanion.detect.RoadBand.toFullFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadBandTest {

    @Test
    fun stripTopMapsToRowSixHundredOfAThousandAndEighty() {
        val full = Detection(0.1f, 0f, 0.3f, 0f, 0.9f).toFullFrame()
        assertEquals(600f / 1080f, full.top, 1e-5f)
    }

    @Test
    fun stripBottomMapsToBottomOfFrame() {
        val full = Detection(0.1f, 0.2f, 0.3f, 1f, 0.9f).toFullFrame()
        assertEquals(1f, full.bottom, 1e-5f)
    }

    @Test
    fun middleOfStripMapsToMiddleOfBand() {
        // The strip spans 0.5556..1.0 of the frame, so its midpoint is 0.7778.
        val full = Detection(0f, 0.5f, 1f, 0.5f, 0.9f).toFullFrame()
        assertEquals(0.5f * (600f / 1080f + 1f), full.top, 1e-5f)
    }

    @Test
    fun horizontalPositionAndConfidenceAreUnchanged() {
        val full = Detection(0.25f, 0.2f, 0.75f, 0.6f, 0.42f).toFullFrame()
        assertEquals(0.25f, full.left, 0f)
        assertEquals(0.75f, full.right, 0f)
        assertEquals(0.42f, full.confidence, 0f)
    }
}
