package com.boat.dashcamcompanion.wifi

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Detects the phone's current Wi-Fi link and (once it looks like a camera AP) binds
 * this process's sockets to that network so HTTP/RTSP calls to the camera don't get
 * routed over mobile data instead. Camera hotspots are usually internet-less, so plain
 * app traffic would otherwise avoid them by default.
 */
class DashcamWifiManager(private val context: Context) {

    data class WifiLinkInfo(
        val ssid: String?,
        val gatewayIp: String?,
        val network: Network?,
        val boundToCamera: Boolean
    )

    data class NearbyNetwork(val ssid: String, val signalLevel: Int)

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _linkInfo = MutableStateFlow(WifiLinkInfo(null, null, null, false))
    val linkInfo: StateFlow<WifiLinkInfo> = _linkInfo

    private val _nearbyDashcamNetworks = MutableStateFlow<List<NearbyNetwork>>(emptyList())
    val nearbyDashcamNetworks: StateFlow<List<NearbyNetwork>> = _nearbyDashcamNetworks

    private val mainHandler = Handler(Looper.getMainLooper())

    fun openSystemWifiSettings() {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Polls the currently associated SSID + gateway until the caller stops collecting. */
    suspend fun pollCurrentWifi() {
        while (true) {
            refreshOnce()
            delay(1500)
        }
    }

    private fun refreshOnce() {
        @Suppress("DEPRECATION")
        val ssidRaw = wifiManager.connectionInfo?.ssid
        val ssid = ssidRaw?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        @Suppress("DEPRECATION")
        val dhcp = wifiManager.dhcpInfo
        val gateway = dhcp?.gateway?.takeIf { it != 0 }?.let { intToIp(it) }

        _linkInfo.value = _linkInfo.value.copy(ssid = ssid, gatewayIp = gateway)

        try {
            @Suppress("DEPRECATION")
            val results = wifiManager.scanResults
            _nearbyDashcamNetworks.value = results
                .mapNotNull { result ->
                    @Suppress("DEPRECATION")
                    val resultSsid = result.SSID
                    if (resultSsid.isNullOrBlank()) return@mapNotNull null
                    if (!WifiUtils.looksLikeCameraSsid(resultSsid)) return@mapNotNull null
                    NearbyNetwork(resultSsid, result.level)
                }
                .distinctBy { it.ssid }
        } catch (e: SecurityException) {
            // Location/nearby-Wi-Fi permission not granted yet; leave the list empty.
        }
    }

    /**
     * Binds this process's default network to the active Wi-Fi network, so all
     * subsequent sockets (MJPEG/RTSP requests) go over the camera's AP even if the
     * phone also has mobile data or another network available.
     */
    fun bindToCurrentWifi(onResult: (success: Boolean) -> Unit) {
        // Camera hotspots have no internet uplink, so the internet capability that
        // NetworkRequest.Builder() requires by default must be explicitly dropped,
        // otherwise this Wi-Fi link can never satisfy the request.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                Log.i(TAG, "onAvailable network=$network capabilities=$caps")
                val bound = connectivityManager.bindProcessToNetwork(network)
                Log.i(TAG, "bindProcessToNetwork returned $bound")
                _linkInfo.value = _linkInfo.value.copy(network = network, boundToCamera = bound)
                connectivityManager.unregisterNetworkCallback(this)
                mainHandler.post { onResult(bound) }
            }

            override fun onUnavailable() {
                Log.w(TAG, "onUnavailable: no network satisfied the request within the timeout")
                connectivityManager.unregisterNetworkCallback(this)
                mainHandler.post { onResult(false) }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "onLost network=$network")
            }
        }

        try {
            Log.i(TAG, "requestNetwork() called, current SSID=${_linkInfo.value.ssid}")
            connectivityManager.requestNetwork(request, callback, 8000)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork() threw", e)
            onResult(false)
        }
    }

    companion object {
        private const val TAG = "DashcamWifi"
    }

    fun unbind() {
        connectivityManager.bindProcessToNetwork(null)
        _linkInfo.value = _linkInfo.value.copy(network = null, boundToCamera = false)
    }

    private fun intToIp(addr: Int): String = WifiUtils.intToIp(addr)
}
