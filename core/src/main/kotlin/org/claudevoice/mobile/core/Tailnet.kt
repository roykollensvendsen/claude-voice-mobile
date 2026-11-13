package org.claudevoice.mobile.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Duration

/** One network the phone has, as the app sees it: whether it is a VPN, and its addresses. */
data class SeenNetwork(val isVpn: Boolean, val addresses: List<InetAddress>)

/**
 * Whether the hub can reach this phone, and if not, why. The app turns each state into words
 * and a button; the core gives the state, never the words.
 */
sealed interface TailnetState {
    /**
     * On the tailnet, at [address] (the IPv4 one when there is one), and the hub is answering
     * or has not been asked yet.
     */
    data class OnTailnet(val address: InetAddress) : TailnetState

    /** No VPN at all: Tailscale looks like it is off. */
    data object TailscaleOff : TailnetState

    /** A VPN without a tailnet address. A guess: Android runs one VPN at a time. */
    data object OtherVpn : TailnetState

    /** On the tailnet, at [address], but the hub is not answering. */
    data class HubNotAnswering(val address: InetAddress) : TailnetState
}

object Tailnet {
    /**
     * A tailnet address: 100.64.0.0/10 or fd7a:115c:a1e0::/48. Only meaningful on the VPN
     * network, because mobile carriers hand out 100.64 addresses too.
     */
    fun isTailnetAddress(address: InetAddress): Boolean {
        val b = address.address
        return when (address) {
            is Inet4Address -> b[0].toInt() and 0xff == 100 && b[1].toInt() and 0xc0 == 0x40
            is Inet6Address -> TAILNET_V6.indices.all { b[it] == TAILNET_V6[it] }
            else -> false
        }
    }

    /**
     * The state from the networks the phone has now, and whether the hub answered last time
     * (null: not known yet). Addresses on any network other than the VPN are ignored.
     */
    fun state(networks: List<SeenNetwork>, hubAnswering: Boolean?): TailnetState {
        val vpns = networks.filter { it.isVpn }
        if (vpns.isEmpty()) return TailnetState.TailscaleOff
        val tailnet = vpns.flatMap { it.addresses }.filter(::isTailnetAddress)
        // Protocol v1 listens on the IPv4 address. An IPv6-only tailnet still counts as on, for
        // the message, though the listener cannot serve version 1 there.
        val address = tailnet.firstOrNull { it is Inet4Address } ?: tailnet.firstOrNull() ?: return TailnetState.OtherVpn
        return if (hubAnswering == false) TailnetState.HubNotAnswering(address) else TailnetState.OnTailnet(address)
    }

    private val TAILNET_V6 = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
}

/**
 * How long to wait before checking a silent hub again: from 30 seconds, doubling, up to
 * 15 minutes, so the check never keeps the phone's radio awake. Starts over once it answers.
 */
class HubCheckBackoff {
    private var next = FIRST

    fun afterFailure(): Duration = next.also { next = minOf(next.multipliedBy(2), LONGEST) }

    fun answered() {
        next = FIRST
    }

    private companion object {
        val FIRST: Duration = Duration.ofSeconds(30)
        val LONGEST: Duration = Duration.ofMinutes(15)
    }
}
