package com.kenny.tellofollow.tello

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import kotlin.coroutines.resume

/**
 * Bind process/sockets to Wi-Fi so UDP to Tello is not sent over cellular.
 */
class TelloWifiBinder(context: Context) {

    companion object {
        private const val TAG = "TelloWifiBinder"
        private const val BIND_TIMEOUT_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val cm =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var boundNetwork: Network? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun currentSsid(): String {
        @Suppress("DEPRECATION")
        val raw = wifi.connectionInfo?.ssid?.trim('"') ?: return "(none)"
        return if (raw.equals("<unknown ssid>", ignoreCase = true) || raw == "0x") {
            "(SSID hidden until Location allowed)"
        } else {
            raw
        }
    }

    fun looksLikeTelloSsid(): Boolean {
        val s = currentSsid()
        return s.startsWith("TELLO-", ignoreCase = true) ||
            s.startsWith("TELLO", ignoreCase = true)
    }

    /** Phone's Wi-Fi IPv4, e.g. 192.168.10.2 when on Tello AP. */
    fun wifiIpv4(): String {
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo?.ipAddress ?: return "(none)"
        if (ip == 0) return "(none)"
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
    }

    fun looksLikeTelloSubnet(): Boolean = wifiIpv4().startsWith("192.168.10.")

    /** @return null on success, or a short reason on failure */
    suspend fun bindToWifi(): String? {
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        if (wifiNetwork != null) {
            return bindNow(wifiNetwork)
        }

        val requested = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    cm.requestNetwork(request, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "requestNetwork failed", e)
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    try {
                        cm.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return if (requested != null) {
            bindNow(requested)
        } else {
            "No Wi-Fi — join TELLO-xxxx in phone Settings first"
        }
    }

    private fun bindNow(network: Network): String? {
        if (!cm.bindProcessToNetwork(network)) {
            return "Could not bind app to Wi-Fi"
        }
        boundNetwork = network
        try {
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("tello-follow").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed", e)
        }
        Log.i(TAG, "Bound to Wi-Fi network=$network ssid=${currentSsid()} ip=${wifiIpv4()}")
        return null
    }

    fun bindSocket(socket: DatagramSocket) {
        val n = boundNetwork ?: return
        try {
            n.bindSocket(socket)
        } catch (e: Exception) {
            Log.w(TAG, "bindSocket failed", e)
        }
    }

    fun unbind() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        boundNetwork = null
    }
}
