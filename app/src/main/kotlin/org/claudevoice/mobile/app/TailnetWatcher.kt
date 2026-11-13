package org.claudevoice.mobile.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.claudevoice.mobile.core.SeenNetwork
import org.claudevoice.mobile.core.Tailnet
import org.claudevoice.mobile.core.TailnetState

/**
 * Feeds the core what the phone's default network is. When Tailscale is on, its VPN is the
 * default network for this app, so this sees it; a plain network request would not, because it
 * asks for networks that are not VPNs. Addresses are read as they change, because one can arrive
 * after the network does.
 */
class TailnetWatcher(context: Context, private val onState: (TailnetState) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var isVpn = false
    private var addresses = emptyList<java.net.InetAddress>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            report()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            addresses = properties.linkAddresses.map { it.address }
            report()
        }

        override fun onLost(network: Network) {
            isVpn = false
            addresses = emptyList()
            report()
        }
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)

    fun stop() = connectivity.unregisterNetworkCallback(callback)

    private fun report() {
        // Whether the hub answers comes from the listener, which is not built yet.
        onState(Tailnet.state(listOf(SeenNetwork(isVpn, addresses)), hubAnswering = null))
    }
}
