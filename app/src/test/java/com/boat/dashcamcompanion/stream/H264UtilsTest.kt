package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class H264UtilsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `removes a four byte start code`() {
        assertArrayEquals(bytes(0x67, 0x64, 0x00), H264Utils.stripStartCode(bytes(0, 0, 0, 1, 0x67, 0x64, 0x00)))
    }

    @Test
    fun `removes a three byte start code`() {
        assertArrayEquals(bytes(0x68, 0xEE), H264Utils.stripStartCode(bytes(0, 0, 1, 0x68, 0xEE)))
    }

    @Test
    fun `leaves a bare nal unit untouched`() {
        val nal = bytes(0x67, 0x42, 0x00, 0x1E)
        assertArrayEquals(nal, H264Utils.stripStartCode(nal))
    }

    @Test
    fun `leaves short input untouched`() {
        assertArrayEquals(bytes(0, 0), H264Utils.stripStartCode(bytes(0, 0)))
    }
}
