package dev.lemon.rex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal data class NetworkRoute(
    val id: String?,
    val transports: Int = 0,
    val validated: Boolean = false,
)

internal data class NetworkChange(val available: Boolean, val reroute: Boolean, val retry: Boolean)

internal class NetworkRouteTracker {
    private var initialized = false
    private var previous = NetworkRoute(null)

    @Synchronized
    fun update(route: NetworkRoute): NetworkChange? {
        if (initialized && route == previous) return null
        val reroute =
            initialized &&
                route.id != null &&
                (previous.id != route.id || previous.transports != route.transports)
        val retry =
            reroute || (initialized && route.id != null && !previous.validated && route.validated)
        previous = route
        initialized = true
        return NetworkChange(route.id != null, reroute, retry)
    }
}

internal class NetworkMonitor(context: Context, private val onChange: (NetworkChange) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = NetworkRouteTracker()
    @Volatile private var running = false
    @Volatile private var current: Network? = null

    private fun report(network: Network?, caps: NetworkCapabilities?) {
        val transports =
            if (caps == null) 0
            else
                listOf(
                        NetworkCapabilities.TRANSPORT_WIFI,
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        NetworkCapabilities.TRANSPORT_ETHERNET,
                        NetworkCapabilities.TRANSPORT_VPN,
                    )
                    .fold(0) { mask, t -> if (caps.hasTransport(t)) mask or (1 shl t) else mask }
        if (running)
            tracker
                .update(
                    NetworkRoute(
                        network?.toString(),
                        transports,
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    ),
                )
                ?.let(onChange)
    }

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == current) report(network, caps)
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    report(null, null)
                }
            }
        }

    fun start() {
        if (running) return
        running = true
        try {
            current = manager.activeNetwork
            report(current, current?.let(manager::getNetworkCapabilities))
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: SecurityException) {
            running = false
        }
    }

    fun stop() {
        if (running) {
            running = false
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}
