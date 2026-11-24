package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** The SDP below is the DESCRIBE reply of a real boAt Hive F1 Pro (audio listed before video). */
class SdpParserF1ProTest {

    private val sdp = """
        v=0
        a=type:broadcast
        a=control:*
        a=range:npt=0-
        m=audio 0 RTP/AVP 96
        b=AS:96
        c=IN IP4 0.0.0.0
        a=rtpmap:96 MPEG4-GENERIC/0/0
        a=fmtp:96 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=0e8056e500
        a=control:track1
        m=video 0 RTP/AVP 97
        b=AS:1000
        c=IN IP4 0.0.0.0
        a=rtpmap:97 H264/90000
        a=fmtp:97 profile-level-id=640033;packetization-mode=1;sprop-parameter-sets=AAAAAWdkADOs6A8BFJogAAB9AAAYahHhEI1A,AAAAAWjuPLA=
        a=control:track2
    """.trimIndent()

    @Test
    fun `audio track control does not replace the whole-session control`() {
        assertEquals("*", SdpParser.parseSdp(sdp).aggregateControl)
    }

    @Test
    fun `picks the video track even though audio comes first`() {
        assertEquals("track2", SdpParser.parseSdp(sdp).trackControl)
    }

    @Test
    fun `reads the parameter sets from the video block`() {
        assertEquals(
            "AAAAAWdkADOs6A8BFJogAAB9AAAYahHhEI1A,AAAAAWjuPLA=",
            SdpParser.parseSdp(sdp).spropParameterSets
        )
    }

    @Test
    fun `reads the video payload type so video can be found on any channel`() {
        // The F1 Pro sends this payload type (97) on interleaved channel 2, not the 0 it agreed to.
        assertEquals(97, SdpParser.parseSdp(sdp).videoPayloadType)
    }

    @Test
    fun `payload type of an older single-track camera is still read`() {
        val older = "v=0\na=control:*\nm=video 0 RTP/AVP 96\na=control:trackID=0"
        assertEquals(96, SdpParser.parseSdp(older).videoPayloadType)
    }

    @Test
    fun `no payload type when there is no video track`() {
        assertEquals(null, SdpParser.parseSdp("v=0\nm=audio 0 RTP/AVP 0").videoPayloadType)
    }

    @Test
    fun `setup and play addresses match what the camera expects`() {
        val info = SdpParser.parseSdp(sdp)
        val aggregate = SdpParser.resolveControlUrl("rtsp://192.168.169.1", info.aggregateControl)
        assertEquals("rtsp://192.168.169.1", aggregate)
        assertEquals("rtsp://192.168.169.1/track2", SdpParser.resolveControlUrl(aggregate, info.trackControl))
    }
}
