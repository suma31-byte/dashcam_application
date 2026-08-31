package com.boat.dashcamcompanion.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiUtilsTest {

    // --- intToIp ---

    @Test
    fun `intToIp converts a little-endian DHCP gateway int to a dotted ip`() {
        // 192.168.1.1 encoded little-endian, as Android's DhcpInfo.gateway returns it:
        // byte0=192, byte1=168, byte2=1, byte3=1 -> int = 1 | (1 shl 8) | (168 shl 16) | (192 shl 24)
        val addr = 192 or (168 shl 8) or (1 shl 16) or (1 shl 24)

        assertEquals("192.168.1.1", WifiUtils.intToIp(addr))
    }

    @Test
    fun `intToIp handles the all-zero address`() {
        assertEquals("0.0.0.0", WifiUtils.intToIp(0))
    }

    @Test
    fun `intToIp handles a common camera gateway address`() {
        // 192.168.1.254, little-endian encoded
        val addr = 192 or (168 shl 8) or (1 shl 16) or (254 shl 24)

        assertEquals("192.168.1.254", WifiUtils.intToIp(addr))
    }

    // --- looksLikeCameraSsid ---

    @Test
    fun `recognizes ssids containing known camera hints`() {
        assertTrue(WifiUtils.looksLikeCameraSsid("Hive_Cam_42"))
        assertTrue(WifiUtils.looksLikeCameraSsid("MyDashcam"))
        assertTrue(WifiUtils.looksLikeCameraSsid("boAt-Recorder"))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertTrue(WifiUtils.looksLikeCameraSsid("HIVE-CAM"))
        assertTrue(WifiUtils.looksLikeCameraSsid("dashCAM_2"))
    }

    @Test
    fun `does not flag unrelated ssids`() {
        assertFalse(WifiUtils.looksLikeCameraSsid("Starbucks WiFi"))
        assertFalse(WifiUtils.looksLikeCameraSsid("MyHomeNetwork"))
        assertFalse(WifiUtils.looksLikeCameraSsid(""))
    }
}
