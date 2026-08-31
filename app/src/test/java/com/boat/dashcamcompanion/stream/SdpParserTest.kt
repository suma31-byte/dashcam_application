package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdpParserTest {

    // --- parseSdp ---

    @Test
    fun `parses aggregate control, track control, and sprop from a typical LIVE555 SDP`() {
        val sdp = """
            v=0
            o=- 0 0 IN IP4 192.168.1.254
            s=Session
            a=control:*
            t=0 0
            m=video 0 RTP/AVP 96
            c=IN IP4 0.0.0.0
            a=control:trackID=0
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IAHpZUCg==,aM48gA==
        """.trimIndent()

        val info = SdpParser.parseSdp(sdp)

        assertEquals("*", info.aggregateControl)
        assertEquals("trackID=0", info.trackControl)
        assertEquals("Z0IAHpZUCg==,aM48gA==", info.spropParameterSets)
    }

    @Test
    fun `returns null sprop when there is no video track`() {
        val sdp = """
            v=0
            s=Session
            a=control:*
            m=audio 0 RTP/AVP 0
            a=control:trackID=0
        """.trimIndent()

        val info = SdpParser.parseSdp(sdp)

        assertNull(info.spropParameterSets)
    }

    @Test
    fun `does not leak an audio track control line into the video track control`() {
        val sdp = """
            v=0
            a=control:*
            m=audio 0 RTP/AVP 0
            a=control:trackID=audio0
            m=video 0 RTP/AVP 96
            a=control:trackID=video0
            a=fmtp:96 sprop-parameter-sets=AAAA
        """.trimIndent()

        val info = SdpParser.parseSdp(sdp)

        assertEquals("trackID=video0", info.trackControl)
    }

    @Test
    fun `handles empty or malformed sdp without crashing`() {
        val info = SdpParser.parseSdp("")

        assertNull(info.aggregateControl)
        assertNull(info.trackControl)
        assertNull(info.spropParameterSets)
    }

    @Test
    fun `sprop key match is case-insensitive`() {
        val sdp = """
            m=video 0 RTP/AVP 96
            a=fmtp:96 SPROP-PARAMETER-SETS=AAAA
        """.trimIndent()

        val info = SdpParser.parseSdp(sdp)

        assertEquals("AAAA", info.spropParameterSets)
    }

    // --- resolveControlUrl ---

    @Test
    fun `resolveControlUrl returns base when control is null`() {
        assertEquals(
            "rtsp://192.168.1.254/live",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live", null)
        )
    }

    @Test
    fun `resolveControlUrl returns base when control is empty`() {
        assertEquals(
            "rtsp://192.168.1.254/live",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live", "")
        )
    }

    @Test
    fun `resolveControlUrl returns base when control is aggregate wildcard`() {
        assertEquals(
            "rtsp://192.168.1.254/live",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live", "*")
        )
    }

    @Test
    fun `resolveControlUrl uses an absolute control url as-is`() {
        assertEquals(
            "rtsp://other-host/track1",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live", "rtsp://other-host/track1")
        )
    }

    @Test
    fun `resolveControlUrl joins a relative control onto a base without trailing slash`() {
        assertEquals(
            "rtsp://192.168.1.254/live/trackID=0",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live", "trackID=0")
        )
    }

    @Test
    fun `resolveControlUrl joins a relative control onto a base with trailing slash`() {
        assertEquals(
            "rtsp://192.168.1.254/live/trackID=0",
            SdpParser.resolveControlUrl("rtsp://192.168.1.254/live/", "trackID=0")
        )
    }
}
