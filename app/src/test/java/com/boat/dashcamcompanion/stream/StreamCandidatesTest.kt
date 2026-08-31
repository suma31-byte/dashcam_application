package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCandidatesTest {

    @Test
    fun `includes the confirmed-working boAt Hive Cam URL first`() {
        val candidates = StreamCandidates.rtspCandidates("192.168.4.1")

        // gatewayIp + :554 + /live is confirmed working against real hardware
        // (see StreamCandidates KDoc) and should be tried before any fallback guess.
        assertEquals("rtsp://192.168.4.1:554/live", candidates.first())
    }

    @Test
    fun `contains gateway ip combined with every port and path`() {
        val gatewayIp = "10.0.0.1"
        val candidates = StreamCandidates.rtspCandidates(gatewayIp)

        val expectedForGateway = listOf(
            "rtsp://10.0.0.1:554/live",
            "rtsp://10.0.0.1:554/livestream",
            "rtsp://10.0.0.1:554/stream1",
            "rtsp://10.0.0.1:554/ch0",
            "rtsp://10.0.0.1:554/av0_0",
            "rtsp://10.0.0.1:554/0",
            "rtsp://10.0.0.1:554",
            "rtsp://10.0.0.1:8554/live",
            "rtsp://10.0.0.1/live"
        )

        expectedForGateway.forEach { expected ->
            assertTrue("Expected $expected in candidates", candidates.contains(expected))
        }
    }

    @Test
    fun `does not duplicate urls when gateway ip matches a fallback host`() {
        // 192.168.1.254 is one of the hardcoded fallback hosts; passing it as the
        // gateway should not produce two copies of the same URL.
        val candidates = StreamCandidates.rtspCandidates("192.168.1.254")

        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `still returns a well-formed list for a blank gateway ip`() {
        val candidates = StreamCandidates.rtspCandidates("")

        assertFalse(candidates.isEmpty())
        assertTrue(candidates.all { it.startsWith("rtsp://") })
    }

    @Test
    fun `includes known fallback hosts alongside the gateway`() {
        val candidates = StreamCandidates.rtspCandidates("172.16.0.1")

        assertTrue(candidates.any { it.startsWith("rtsp://192.168.1.254") })
        assertTrue(candidates.any { it.startsWith("rtsp://192.168.25.1") })
        assertTrue(candidates.any { it.startsWith("rtsp://192.168.169.1") })
        assertTrue(candidates.any { it.startsWith("rtsp://192.168.42.1") })
    }
}
