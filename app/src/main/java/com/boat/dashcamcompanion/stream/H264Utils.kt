package com.boat.dashcamcompanion.stream

object H264Utils {

    /**
     * Some cameras put the Annex-B start code (00 00 00 01 or 00 00 01) inside the base64
     * sprop-parameter-sets. The decoder input is built by adding a start code in front of every
     * NAL unit, so a second one would leave an empty NAL unit in front of the SPS/PPS.
     */
    fun stripStartCode(nal: ByteArray): ByteArray {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            return nal.copyOfRange(4, nal.size)
        }
        if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
            return nal.copyOfRange(3, nal.size)
        }
        return nal
    }
}
