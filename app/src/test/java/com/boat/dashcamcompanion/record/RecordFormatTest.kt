package com.boat.dashcamcompanion.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFormatTest {

    @Test
    fun `frame row has index, arrival time, rtp timestamp and size`() {
        assertEquals("5,1700000000123,90000,4096", RecordFormat.frameLine(5, 1700000000123L, 90000L, 4096))
    }

    @Test
    fun `gps row uses dots for decimals and fixed precision`() {
        val line = RecordFormat.gpsLine(1L, 2L, 12.9715987, 77.5945627, 5.5f, 90f, 4f, 900.0)
        assertEquals("1,2,12.9715987,77.5945627,5.50,90.0,4.0,900.0", line)
    }

    @Test
    fun `gps values the phone could not provide are left empty`() {
        val line = RecordFormat.gpsLine(1L, 2L, 1.0, 2.0, null, Float.NaN, null, null)
        assertEquals("1,2,1.0000000,2.0000000,,,,", line)
    }

    @Test
    fun `gps row has as many columns as the header`() {
        val row = RecordFormat.gpsLine(1L, 2L, 1.0, 2.0, 3f, 4f, 5f, 6.0)
        assertEquals(RecordFormat.GPS_HEADER.split(",").size, row.split(",").size)
    }

    @Test
    fun `frame row has as many columns as the header`() {
        val row = RecordFormat.frameLine(0, 1, 2, 3)
        assertEquals(RecordFormat.FRAMES_HEADER.split(",").size, row.split(",").size)
    }

    @Test
    fun `duration is minutes and seconds`() {
        assertEquals("02:05", RecordFormat.duration(125_000))
        assertEquals("00:00", RecordFormat.duration(-5))
    }

    @Test
    fun `size is shown in megabytes`() {
        assertEquals("1.5 MB", RecordFormat.megabytes(1_500_000))
    }

    @Test
    fun `session folder name contains a sortable timestamp`() {
        assertTrue(Regex("session_\\d{8}_\\d{6}").matches(RecordFormat.sessionName(1700000000000L)))
    }
}
