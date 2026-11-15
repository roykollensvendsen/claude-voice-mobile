package org.claudevoice.mobile.core

import java.io.IOException
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListeningTest {
    private val tailnet = InetAddress.getByName("100.64.0.2")

    private class Opened(val address: BindAddress, val port: Int) : Server {
        var stopped = false

        override fun stop() {
            stopped = true
        }
    }

    private val opened = mutableListOf<Opened>()
    private var failing = false
    private val listening = Listening(port = 8812) { address, port ->
        if (failing) throw IOException("port taken")
        Opened(address, port).also { opened += it }
    }

    @Test
    fun `only a tailnet IPv4 address can be bound`() {
        assertEquals(tailnet, BindAddress.of(tailnet)?.address)
        for (other in listOf("0.0.0.0", "::", "127.0.0.1", "::1", "192.168.1.5", "10.0.0.2", "fd7a:115c:a1e0::2", "100.128.0.1")) {
            assertNull(BindAddress.of(InetAddress.getByName(other)), other)
        }
    }

    @Test
    fun `on the tailnet it listens on that address and port`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        assertEquals(ListenStatus.Listening(tailnet, 8812), listening.status)
        assertEquals(tailnet, opened.single().address.address)
        assertEquals(8812, opened.single().port)
    }

    @Test
    fun `a hub not answering does not stop the phone listening`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        listening.follow(TailnetState.HubNotAnswering(tailnet))
        assertEquals(1, opened.size)
        assertEquals(false, opened.single().stopped)
    }

    @Test
    fun `the same address again opens nothing new`() {
        repeat(3) { listening.follow(TailnetState.OnTailnet(tailnet)) }
        assertEquals(1, opened.size)
    }

    @Test
    fun `when the tailnet goes away it stops and says why, and listens nowhere else`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        for (gone in listOf(TailnetState.TailscaleOff, TailnetState.OtherVpn, TailnetState.NoNetwork)) {
            listening.follow(gone)
            assertTrue(opened.all { it.stopped }, "$gone")
            assertTrue(listening.status is ListenStatus.Stopped, "$gone")
            assertEquals(1, opened.size, "nothing was opened instead")
        }
    }

    @Test
    fun `a new tailnet address stops the old one before opening the new`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        val moved = InetAddress.getByName("100.64.0.9")
        listening.follow(TailnetState.OnTailnet(moved))
        assertTrue(opened[0].stopped)
        assertEquals(moved, opened[1].address.address)
        assertEquals(ListenStatus.Listening(moved, 8812), listening.status)
    }

    @Test
    fun `an address that is not one it may bind stops it, and opens nothing`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        listening.follow(TailnetState.OnTailnet(InetAddress.getByName("fd7a:115c:a1e0::2")))
        assertTrue(opened.single().stopped)
        val status = listening.status as ListenStatus.Stopped
        assertTrue(status.reason.contains("IPv4"), status.reason)
    }

    @Test
    fun `a bind that fails is reported, not retried elsewhere`() {
        failing = true
        listening.follow(TailnetState.OnTailnet(tailnet))
        val status = listening.status as ListenStatus.Stopped
        assertTrue(status.reason.contains("port taken"), status.reason)
        assertTrue(status.reason.contains("8812"), status.reason)
    }

    @Test
    fun `before any tailnet state it is stopped`() {
        assertTrue(listening.status is ListenStatus.Stopped)
    }

    @Test
    fun `stopping for good closes the server`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        listening.stop()
        assertTrue(opened.single().stopped)
        assertTrue(listening.status is ListenStatus.Stopped)
    }

    @Test
    fun `after stopping for good, a late tailnet state opens nothing`() {
        listening.follow(TailnetState.OnTailnet(tailnet))
        listening.stop()
        listening.follow(TailnetState.OnTailnet(InetAddress.getByName("100.64.0.9")))
        listening.follow(TailnetState.OnTailnet(tailnet))
        assertEquals(1, opened.size)
        assertTrue(listening.status is ListenStatus.Stopped)
    }

    @Test
    fun `each way off the tailnet says what to do`() {
        val reasons = listOf(TailnetState.TailscaleOff, TailnetState.OtherVpn, TailnetState.NoNetwork).map {
            listening.follow(it)
            (listening.status as ListenStatus.Stopped).reason
        }
        assertTrue(reasons[0].contains("turn on Tailscale"), reasons[0])
        assertTrue(reasons[1].contains("turn off the other VPN"), reasons[1])
        assertTrue(reasons[2].contains("connect to a network"), reasons[2])
    }
}
