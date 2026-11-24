package com.boat.dashcamcompanion.stream

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wake-up handshake for cameras that use the "YiZhi" HTTP API, such as the boAt Hive F1 Pro.
 *
 * These cameras keep their RTSP server (port 554) closed until the phone asks over plain HTTP
 * (port 80) to enter recorder mode, which is what the manufacturer's own app does before it plays
 * the stream. Without it every RTSP connection is refused and the live view stays empty.
 *
 * The sequence, taken from the manufacturer app's behaviour against a real F1 Pro:
 *   GET /app/getproductinfo -> {"result":0,"info":{"model":"F1PRO",...}}
 *   GET /app/getmediainfo   -> {"result":0,"info":{"rtsp":"rtsp://192.168.169.1","transport":"tcp",...}}
 *   GET /app/enterrecorder  -> {"result":0,"info":"enter_recorder success"}
 */
object YiZhiHandshake {

    data class Result(val rtspUrl: String, val model: String?)

    /**
     * Returns null when the camera does not speak this API, so the caller can fall back to
     * guessing ordinary RTSP addresses.
     */
    fun enter(gatewayIp: String): Result? {
        val product = get(gatewayIp, "/app/getproductinfo") ?: return null
        if (!isSuccess(product)) return null
        val media = get(gatewayIp, "/app/getmediainfo")
        val entered = get(gatewayIp, "/app/enterrecorder") ?: return null
        if (!isSuccess(entered)) return null
        return Result(
            rtspUrl = media?.let(::parseRtspUrl) ?: "rtsp://$gatewayIp",
            model = parseModel(product)
        )
    }

    /** Pure parsing helpers, kept separate so they can be unit tested without a camera. */
    internal fun isSuccess(json: String): Boolean = RESULT_OK.containsMatchIn(json)

    internal fun parseRtspUrl(json: String): String? =
        RTSP_URL.find(json)?.groupValues?.get(1)?.takeIf { it.startsWith("rtsp://") }

    internal fun parseModel(json: String): String? = MODEL.find(json)?.groupValues?.get(1)

    private val RESULT_OK = Regex(""""result"\s*:\s*0\b""")
    private val RTSP_URL = Regex(""""rtsp"\s*:\s*"([^"]+)"""")
    private val MODEL = Regex(""""model"\s*:\s*"([^"]+)"""")

    private fun get(host: String, path: String): String? {
        val connection = try {
            URL("http://$host$path").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 3000
}
