package com.boat.dashcamcompanion.stream

/**
 * Common Wi-Fi dashcam RTSP URL conventions, tried in order against whatever gateway IP
 * the camera's DHCP hands out. gatewayIp + port 554 + "/live" is confirmed working against
 * boAt's Hive Cam hardware (a LIVE555-based RTSP server); the rest are fallback guesses for
 * other firmware.
 */
object StreamCandidates {

    fun rtspCandidates(gatewayIp: String): List<String> {
        val hosts = listOf(gatewayIp, "192.168.1.254", "192.168.25.1", "192.168.169.1", "192.168.42.1")
            .distinct()
        val paths = listOf(
            "/live",
            "/livestream",
            "/stream1",
            "/ch0",
            "/av0_0",
            "/0",
            ""
        )
        val ports = listOf(":554", ":8554", "")

        return hosts.flatMap { host ->
            ports.flatMap { port ->
                paths.map { path -> "rtsp://$host$port$path" }
            }
        }
    }
}
