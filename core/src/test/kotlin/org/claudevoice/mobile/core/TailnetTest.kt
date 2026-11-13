package org.claudevoice.mobile.core

import java.net.InetAddress
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TailnetTest {
    private fun ip(literal: String) = InetAddress.getByName(literal)

    private fun vpn(vararg a: String) = SeenNetwork(isVpn = true, addresses = a.map(::ip))

    private fun cellular(vararg a: String) = SeenNetwork(isVpn = false, addresses = a.map(::ip))

    @Test
    fun `tailnet addresses are 100_64_0_0 slash 10 and fd7a_115c_a1e0 slash 48`() {
        assertTrue(Tailnet.isTailnetAddress(ip("100.64.0.1")))
        assertTrue(Tailnet.isTailnetAddress(ip("100.127.255.254")))
        assertFalse(Tailnet.isTailnetAddress(ip("100.128.0.1")))
        assertFalse(Tailnet.isTailnetAddress(ip("100.63.255.255")))
        assertFalse(Tailnet.isTailnetAddress(ip("192.0.2.10")))
        assertTrue(Tailnet.isTailnetAddress(ip("fd7a:115c:a1e0::1")))
        assertTrue(Tailnet.isTailnetAddress(ip("fd7a:115c:a1e0:ffff::1")))
        assertFalse(Tailnet.isTailnetAddress(ip("fd7a:115c:a1e1::1")))
    }

    @Test
    fun `a tailnet address on the VPN network means the phone is on the tailnet`() {
        val state = Tailnet.state(listOf(cellular("192.0.2.10"), vpn("100.101.102.103")), hubAnswering = null)
        assertEquals(TailnetState.OnTailnet(ip("100.101.102.103")), state)
    }

    @Test
    fun `only an IPv6 tailnet address is enough`() {
        val state = Tailnet.state(listOf(vpn("fd7a:115c:a1e0::5")), hubAnswering = true)
        assertEquals(TailnetState.OnTailnet(ip("fd7a:115c:a1e0::5")), state)
    }

    // Mobile carriers hand out 100.64 addresses too (carrier-grade NAT).
    @Test
    fun `a 100_64 address on the mobile network says nothing`() {
        assertEquals(TailnetState.TailscaleOff, Tailnet.state(listOf(cellular("100.70.1.2")), hubAnswering = null))
    }

    @Test
    fun `no VPN at all means Tailscale is off`() {
        assertEquals(TailnetState.TailscaleOff, Tailnet.state(emptyList(), hubAnswering = null))
    }

    @Test
    fun `a VPN without a tailnet address means another VPN seems to be on`() {
        val state = Tailnet.state(listOf(cellular("192.0.2.10"), vpn("10.8.0.2")), hubAnswering = null)
        assertEquals(TailnetState.OtherVpn, state)
    }

    @Test
    fun `on the tailnet with the hub not answering is its own state`() {
        val state = Tailnet.state(listOf(vpn("100.101.102.103")), hubAnswering = false)
        assertEquals(TailnetState.HubNotAnswering(ip("100.101.102.103")), state)
    }

    @Test
    fun `the hub check backs off slowly and starts over once the hub answers`() {
        val backoff = HubCheckBackoff()
        assertEquals(Duration.ofSeconds(30), backoff.afterFailure())
        assertEquals(Duration.ofSeconds(60), backoff.afterFailure())
        assertEquals(Duration.ofSeconds(120), backoff.afterFailure())
        repeat(10) { backoff.afterFailure() }
        assertEquals(Duration.ofMinutes(15), backoff.afterFailure())
        backoff.answered()
        assertEquals(Duration.ofSeconds(30), backoff.afterFailure())
    }
}
