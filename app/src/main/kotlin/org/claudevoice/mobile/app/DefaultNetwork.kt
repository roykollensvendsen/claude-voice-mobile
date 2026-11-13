package org.claudevoice.mobile.app

import java.net.InetAddress
import org.claudevoice.mobile.core.SeenNetwork

/**
 * What is known about the phone's default network, one network at a time. Android tells the
 * kind (VPN or not) and the addresses in separate calls, and when Wi-Fi hands over to the VPN
 * the VPN's kind arrives before its addresses. So nothing is reported until the current network
 * has both, and news about a network that is no longer the default is dropped.
 *
 * Each call returns the networks to give the core, or null when there is nothing new to say.
 * [K] is Android's Network in the app, and anything in the tests.
 */
class DefaultNetwork<K : Any> {
    private var current: K? = null
    private var isVpn: Boolean? = null
    private var addresses: List<InetAddress>? = null

    fun available(network: K) {
        if (network == current) return
        current = network
        isVpn = null
        addresses = null
    }

    fun capabilities(network: K, isVpn: Boolean): List<SeenNetwork>? {
        if (network != current) return null
        this.isVpn = isVpn
        return known()
    }

    fun addresses(network: K, addresses: List<InetAddress>): List<SeenNetwork>? {
        if (network != current) return null
        this.addresses = addresses
        return known()
    }

    /** The default network went with nothing in its place: no network at all. */
    fun lost(network: K): List<SeenNetwork>? {
        if (network != current) return null
        current = null
        isVpn = null
        addresses = null
        return emptyList()
    }

    private fun known(): List<SeenNetwork>? {
        val vpn = isVpn ?: return null
        val addresses = addresses ?: return null
        return listOf(SeenNetwork(vpn, addresses))
    }
}
