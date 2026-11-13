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
 * asks for networks that are not VPNs.
 */
class TailnetWatcher(context: Context, private val onState: (TailnetState) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var default = DefaultNetwork<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = default.available(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            report(default.capabilities(network, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            report(default.addresses(network, properties.linkAddresses.map { it.address }))
        }

        override fun onLost(network: Network) = report(default.lost(network))
    }

    fun start() {
        default = DefaultNetwork()
        // With no default network the callback never fires, so say so once here.
        if (connectivity.activeNetwork == null) report(emptyList())
        connectivity.registerDefaultNetworkCallback(callback)
    }

    fun stop() = connectivity.unregisterNetworkCallback(callback)

    private fun report(networks: List<SeenNetwork>?) {
        if (networks == null) return
        // Whether the hub answers comes from the listener, which is not built yet.
        onState(Tailnet.state(networks, hubAnswering = null))
    }
}
