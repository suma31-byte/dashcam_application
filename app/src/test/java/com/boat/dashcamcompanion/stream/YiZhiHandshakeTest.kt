package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses below were captured from a real boAt Hive F1 Pro at 192.168.169.1. */
class YiZhiHandshakeTest {

    private val productInfo =
        """{"result":0,"info":{"model":"F1PRO","company":"HUAXIN","soc":"eeasytech","sp":"HUAXIN"}}"""
    private val mediaInfo =
        """{"result":0,"info":{"rtsp":"rtsp://192.168.169.1","transport":"tcp","port":5000}}"""
    private val enterRecorder = """{"result":0,"info":"enter_recorder success"}"""

    @Test
    fun `recognises successful responses`() {
        assertTrue(YiZhiHandshake.isSuccess(productInfo))
        assertTrue(YiZhiHandshake.isSuccess(mediaInfo))
        assertTrue(YiZhiHandshake.isSuccess(enterRecorder))
    }

    @Test
    fun `treats a rejected command as failure`() {
        assertFalse(YiZhiHandshake.isSuccess("""{"result":-1,"info":"set fail"}"""))
    }

    @Test
    fun `a result code that merely starts with zero is not success`() {
        assertFalse(YiZhiHandshake.isSuccess("""{"result":05}"""))
    }

    @Test
    fun `the root page of the camera is not a product reply`() {
        // GET / returns this on the F1 Pro even before the handshake; it carries no model.
        assertNull(YiZhiHandshake.parseModel("""{"result":0,"info":"url_root success"}"""))
    }

    @Test
    fun `reads the stream address and model`() {
        assertEquals("rtsp://192.168.169.1", YiZhiHandshake.parseRtspUrl(mediaInfo))
        assertEquals("F1PRO", YiZhiHandshake.parseModel(productInfo))
    }

    @Test
    fun `ignores a stream address that is not rtsp`() {
        assertNull(YiZhiHandshake.parseRtspUrl("""{"result":0,"info":{"rtsp":"http://example.com"}}"""))
    }
}
