package com.boat.dashcamcompanion.stream

/**
 * Parses RTSP DESCRIBE responses (SDP bodies) and resolves the LIVE555-style
 * control URLs used by SETUP/PLAY. Pulled out of RtspH264Client as pure,
 * dependency-free logic so it can be unit tested without a device/emulator.
 */
object SdpParser {

    data class SdpInfo(
        val aggregateControl: String?,
        val trackControl: String?,
        val spropParameterSets: String?
    )

    fun parseSdp(body: String): SdpInfo {
        var aggregateControl: String? = null
        var trackControl: String? = null
        var sprop: String? = null
        var inVideoBlock = false
        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("m=video") -> inVideoBlock = true
                line.startsWith("m=") -> inVideoBlock = false
                line.startsWith("a=control:") -> {
                    val value = line.substringAfter("a=control:")
                    if (inVideoBlock) trackControl = value else aggregateControl = value
                }
                inVideoBlock && line.startsWith("a=fmtp:") -> {
                    val params = line.substringAfter(' ')
                    params.split(";").forEach { p ->
                        val kv = p.trim().split("=", limit = 2)
                        if (kv.size == 2 && kv[0].equals("sprop-parameter-sets", ignoreCase = true)) {
                            sprop = kv[1]
                        }
                    }
                }
            }
        }
        return SdpInfo(aggregateControl, trackControl, sprop)
    }

    /**
     * Resolves an SDP `a=control:` value against a base URL, per RFC 2326 §C.1.1:
     * - missing/empty control, or "*" -> the base URL itself (aggregate control)
     * - an absolute "rtsp://" control -> used as-is
     * - anything else -> joined onto the base URL as a relative path
     */
    fun resolveControlUrl(base: String, control: String?): String {
        if (control.isNullOrEmpty() || control == "*") return base
        if (control.startsWith("rtsp://")) return control
        return if (base.endsWith("/")) base + control else "$base/$control"
    }
}
