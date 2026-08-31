package com.boat.dashcamcompanion.wifi

/**
 * Pure helper logic pulled out of DashcamWifiManager so it can be unit tested
 * without a device/emulator (no android.* framework dependencies).
 */
object WifiUtils {

    // Substrings (case-insensitive) that suggest a scanned SSID belongs to a
    // dashcam's own Wi-Fi access point rather than an unrelated network.
    private val CAMERA_SSID_HINTS = listOf("hive", "dashcam", "boat")

    /** Converts a DHCP-provided little-endian gateway int into a dotted-quad IP string. */
    fun intToIp(addr: Int): String {
        return "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"
    }

    /** True if the given SSID looks like it belongs to a dashcam access point. */
    fun looksLikeCameraSsid(ssid: String): Boolean {
        return CAMERA_SSID_HINTS.any { ssid.contains(it, ignoreCase = true) }
    }
}
