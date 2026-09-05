package studio.cluvex.aether.core.tunnel.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet6Address

/**
 * Finds the physical network to use for DIRECT (split-tunnel) flows and
 * describes it for the routing log.
 *
 * Pulled out of the bridge because none of it has anything to do with packets:
 * it is pure ConnectivityManager plumbing, and it is the part most likely to
 * need per-OEM tweaking later.
 */
internal class UnderlyingNetworks(private val cm: ConnectivityManager) {

    /** Best non-VPN network with internet access; prefers a VALIDATED one. */
    fun best(): Network? {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    /** True when [network] has a default IPv6 route, i.e. v6 can actually leave. */
    fun supportsIpv6(network: Network): Boolean =
        cm.getLinkProperties(network)?.routes?.any { route ->
            route.isDefaultRoute && route.destination.address is Inet6Address
        } == true

    fun label(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "physical"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "physical"
        }
    }
}