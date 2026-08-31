package com.boat.dashcamcompanion.stream

/**
 * Maps the camera's 32-bit RTP capture clock (which wraps roughly every 13.25
 * hours at the 90kHz H264 clock rate) onto local playback time, smoothing out
 * network jitter with a small buffer and resyncing if the two clocks drift
 * apart. Pulled out of RtspH264Client as pure, stateful-but-dependency-free
 * logic so it can be unit tested without a device/emulator.
 *
 * @param nowNs clock source for "now", in nanoseconds. Defaults to
 *   System.nanoTime(); tests can inject a fake, controllable clock instead.
 */
class RtpTimestampScheduler(
    private val rtpClockHz: Long = RTP_CLOCK_HZ,
    private val smoothingBufferNs: Long = SMOOTHING_BUFFER_NS,
    private val maxDriftNs: Long = MAX_DRIFT_NS,
    private val nowNs: () -> Long = { System.nanoTime() }
) {
    private var prevRawTimestamp: Long? = null
    private var wrapOffset = 0L
    private var rtpAnchorTicks: Long? = null
    private var localAnchorNs = 0L

    /** Unwraps a raw 32-bit RTP timestamp into a monotonically increasing tick count. */
    fun unwrapTimestamp(raw: Long): Long {
        val prev = prevRawTimestamp
        if (prev != null) {
            val diff = raw - prev
            if (diff < -(1L shl 31)) wrapOffset += (1L shl 32)
            else if (diff > (1L shl 31)) wrapOffset -= (1L shl 32)
        }
        prevRawTimestamp = raw
        return raw + wrapOffset
    }

    /** Converts a raw RTP timestamp into a local presentation-time-in-microseconds. */
    fun scheduledPtsUs(rawTimestamp: Long): Long {
        val ticks = unwrapTimestamp(rawTimestamp)
        val now = nowNs()
        val anchorTicks = rtpAnchorTicks
        if (anchorTicks == null) {
            rtpAnchorTicks = ticks
            localAnchorNs = now + smoothingBufferNs
            return localAnchorNs / 1000
        }
        val ptsNs = localAnchorNs + (ticks - anchorTicks) * 1_000_000_000L / rtpClockHz
        val drift = ptsNs - (now + smoothingBufferNs)
        if (drift > maxDriftNs || drift < -maxDriftNs) {
            rtpAnchorTicks = ticks
            localAnchorNs = now + smoothingBufferNs
            return localAnchorNs / 1000
        }
        return ptsNs / 1000
    }

    companion object {
        const val RTP_CLOCK_HZ = 90000L // standard RTP clock rate for H264 payloads (RFC 6184)
        const val SMOOTHING_BUFFER_NS = 150_000_000L // 150ms jitter buffer
        const val MAX_DRIFT_NS = 400_000_000L // resync threshold
    }
}
