package com.boat.dashcamcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpTimestampSchedulerTest {

    /** Simple controllable fake clock so tests don't depend on real wall-clock time. */
    private class FakeClock(var nowNs: Long = 0L) {
        fun advanceMs(ms: Long) { nowNs += ms * 1_000_000L }
        fun get(): Long = nowNs
    }

    // --- unwrapTimestamp ---

    @Test
    fun `unwrapTimestamp leaves timestamps unchanged when there is no wraparound`() {
        val scheduler = RtpTimestampScheduler()

        assertEquals(1000L, scheduler.unwrapTimestamp(1000L))
        assertEquals(2000L, scheduler.unwrapTimestamp(2000L))
        assertEquals(90000L, scheduler.unwrapTimestamp(90000L))
    }

    @Test
    fun `unwrapTimestamp corrects for 32-bit forward wraparound`() {
        val scheduler = RtpTimestampScheduler()

        val nearMax = 4_294_967_290L // close to 2^32 - 1
        val afterWrap = 100L // wrapped back around to a small value

        scheduler.unwrapTimestamp(nearMax)
        val unwrapped = scheduler.unwrapTimestamp(afterWrap)

        // The unwrapped value should keep increasing monotonically despite the
        // raw 32-bit counter having wrapped back to a small number.
        assertTrue("Expected unwrapped timestamp to keep increasing past wraparound", unwrapped > nearMax)
    }

    @Test
    fun `unwrapTimestamp keeps increasing across multiple wraps`() {
        val scheduler = RtpTimestampScheduler()
        var last = scheduler.unwrapTimestamp(0L)

        // Simulate the RTP clock (90kHz) wrapping twice, in reasonably sized steps.
        val step = 1_000_000_000L
        var raw = 0L
        repeat(20) {
            raw = (raw + step) % (1L shl 32)
            val unwrapped = scheduler.unwrapTimestamp(raw)
            assertTrue("Expected monotonically increasing unwrapped timestamps", unwrapped >= last)
            last = unwrapped
        }
    }

    // --- scheduledPtsUs ---

    @Test
    fun `first frame is scheduled one smoothing buffer ahead of now`() {
        val clock = FakeClock(nowNs = 0L)
        val scheduler = RtpTimestampScheduler(nowNs = clock::get)

        val ptsUs = scheduler.scheduledPtsUs(0L)

        assertEquals(RtpTimestampScheduler.SMOOTHING_BUFFER_NS / 1000, ptsUs)
    }

    @Test
    fun `subsequent frames are spaced according to the rtp clock rate`() {
        val clock = FakeClock(nowNs = 0L)
        val scheduler = RtpTimestampScheduler(nowNs = clock::get)

        scheduler.scheduledPtsUs(0L) // anchor frame
        clock.advanceMs(33) // ~one frame interval at 30fps

        // 90kHz clock, so one 33ms frame interval is ~2970 ticks later.
        val secondPtsUs = scheduler.scheduledPtsUs(2970L)

        val expectedDeltaUs = 2970L * 1_000_000L / RtpTimestampScheduler.RTP_CLOCK_HZ
        val firstPtsUs = RtpTimestampScheduler.SMOOTHING_BUFFER_NS / 1000
        assertEquals(firstPtsUs + expectedDeltaUs, secondPtsUs)
    }

    @Test
    fun `resyncs when local and rtp clocks drift beyond the max drift threshold`() {
        val clock = FakeClock(nowNs = 0L)
        val scheduler = RtpTimestampScheduler(nowNs = clock::get)

        scheduler.scheduledPtsUs(0L) // establishes the anchor

        // Advance the wall clock far more than the RTP ticks would predict,
        // simulating a stall/catch-up scenario that exceeds MAX_DRIFT_NS.
        clock.advanceMs(2000)
        val resyncedPtsUs = scheduler.scheduledPtsUs(1L)

        // After a resync, the new pts should again be one smoothing buffer
        // ahead of the (advanced) current time, not far behind it.
        val expected = (clock.nowNs + RtpTimestampScheduler.SMOOTHING_BUFFER_NS) / 1000
        assertEquals(expected, resyncedPtsUs)
    }
}
