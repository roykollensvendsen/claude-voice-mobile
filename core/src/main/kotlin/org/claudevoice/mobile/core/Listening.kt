package org.claudevoice.mobile.core

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/**
 * An address the phone may listen on: a tailnet IPv4 address, and nothing else. Protocol version
 * 1 listens on the IPv4 address alone. There is no other way to make one, so no server can be
 * opened on 0.0.0.0, on loopback or on the phone's Wi-Fi.
 */
class BindAddress private constructor(val address: InetAddress) {
    companion object {
        fun of(address: InetAddress): BindAddress? =
            if (address is Inet4Address && Tailnet.isTailnetAddress(address)) BindAddress(address) else null
    }
}

/** A server the phone has opened. [stop] closes its listening socket and every open connection. */
fun interface Server {
    fun stop()
}

/** Opens a server on exactly [address] and [port], or throws [IOException]. */
fun interface ServerOpener {
    @Throws(IOException::class)
    fun open(address: BindAddress, port: Int): Server
}

/** Whether the phone is listening for the hub, and where; or why not, in words for the owner. */
sealed interface ListenStatus {
    data class Listening(val address: InetAddress, val port: Int) : ListenStatus

    data class Stopped(val reason: String) : ListenStatus
}

/**
 * Listens on the tailnet address alone, following the tailnet as the phone sees it. When the
 * address changes, the old server stops before a new one opens on the new address. When the
 * tailnet goes away, or the address is not one the phone may bind, or the bind fails, it stops
 * and says why. It never falls back to another address. After [stop] it opens nothing again.
 */
class Listening(private val port: Int, private val opener: ServerOpener) {
    private var server: Server? = null
    private var at: InetAddress? = null
    private var stopped = false

    @Volatile
    var status: ListenStatus = ListenStatus.Stopped(NOT_YET)
        private set

    /** The tailnet as it is now: keep listening, move, or stop. */
    @Synchronized
    fun follow(state: TailnetState) {
        val address = when (state) {
            is TailnetState.OnTailnet -> state.address
            is TailnetState.HubNotAnswering -> state.address
            else -> null
        }
        if (stopped || (address != null && address == at)) return
        close()
        status = when (state) {
            TailnetState.TailscaleOff -> ListenStatus.Stopped(TAILSCALE_OFF)
            TailnetState.OtherVpn -> ListenStatus.Stopped(OTHER_VPN)
            TailnetState.NoNetwork -> ListenStatus.Stopped(NO_NETWORK)
            else -> address?.let(BindAddress::of)?.let(::open) ?: ListenStatus.Stopped(NOT_IPV4)
        }
    }

    /** Stop for good, as when the app's service ends. */
    @Synchronized
    fun stop() {
        stopped = true
        close()
        status = ListenStatus.Stopped(STOPPED)
    }

    private fun open(address: BindAddress): ListenStatus = try {
        server = opener.open(address, port)
        at = address.address
        ListenStatus.Listening(address.address, port)
    } catch (e: IOException) {
        ListenStatus.Stopped(
            "could not listen on ${address.address.hostAddress}:$port: ${e.message}; " +
                "close the app using port $port, or restart the phone",
        )
    }

    private fun close() {
        server?.stop()
        server = null
        at = null
    }

    private companion object {
        const val NOT_YET = "not on the tailnet yet"
        const val TAILSCALE_OFF = "not listening: Tailscale is off; turn on Tailscale on this phone"
        const val OTHER_VPN = "not listening: another VPN is on; turn off the other VPN and turn on Tailscale"
        const val NO_NETWORK = "not listening: no network; connect to a network"
        const val NOT_IPV4 = "not listening: the phone has no tailnet IPv4 address, which protocol version 1 needs"
        const val STOPPED = "stopped"
    }
}
