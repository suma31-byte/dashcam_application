package com.boat.dashcamcompanion.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens an HTTP connection over a network that really has internet.
 *
 * To talk to the dashcam, the app binds itself to the camera's Wi-Fi (bindProcessToNetwork).
 * That Wi-Fi has no internet, so ordinary requests (address lookup, …) failed even when the
 * phone had mobile data or office Wi-Fi. This picks a validated internet network explicitly.
 */
object InternetConnection {
    fun open(context: Context, url: String): HttpURLConnection {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val internet = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val u = URL(url)
        return (internet?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }
}
