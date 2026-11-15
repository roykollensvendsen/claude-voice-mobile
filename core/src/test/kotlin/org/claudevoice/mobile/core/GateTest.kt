package org.claudevoice.mobile.core

import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateTest {
    private val phone = InetAddress.getByName("100.64.0.2")
    private val hub = InetAddress.getByName("100.64.0.1")
    private val key = "k".repeat(43)
    private val paired = PairedHub(PairedHub.hash(key), PinnedAddress(hub))

    private fun gate(hub: PairedHub? = paired, highestSeen: Int = 0) =
        Gate(own = phone, port = 8812, machine = "phone-a", hub = hub, highestSeen = highestSeen)

    private fun request(
        path: String = "/mcp",
        from: InetAddress = hub,
        vararg headers: Pair<String, String> = arrayOf(
            "Host" to "100.64.0.2:8812",
            "Claude-Voice-Protocol" to "1",
            "Authorization" to "Bearer $key",
        ),
    ) = GateRequest(path = path, peer = from, headers = headers.toMap())

    private fun refused(decision: GateDecision) = decision as GateDecision.Refuse

    @Test
    fun `the paired hub's request with its key passes in version 1`() {
        assertEquals(GateDecision.Pass(version = 1), gate().check(request()))
    }

    @Test
    fun `header names are read in any case`() {
        val lower = request(headers = arrayOf("host" to "100.64.0.2:8812", "claude-voice-protocol" to "1", "authorization" to "Bearer $key"))
        assertEquals(GateDecision.Pass(version = 1), gate().check(lower))
    }

    @Test
    fun `a hub that also knows a later version is answered in 1`() {
        val r = request(headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "1,2", "Authorization" to "Bearer $key"))
        assertEquals(GateDecision.Pass(version = 1), gate().check(r))
    }

    @Test
    fun `no shared version is refused with the versions the phone speaks`() {
        val r = refused(gate().check(request(headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "2"))))
        assertEquals(400, r.status)
        assertEquals("unsupported_version", r.code)
        assertEquals(listOf(1), r.versions)
        assertEquals("1", r.headers["Claude-Voice-Protocol"])
    }

    @Test
    fun `no version header is version 0, and accepted until the hub has spoken 1`() {
        val v0 = request(headers = arrayOf("Host" to "100.64.0.2:8812", "Authorization" to "Bearer $key"))
        assertEquals(GateDecision.Pass(version = 0), gate().check(v0))
        assertEquals("unsupported_version", refused(gate(highestSeen = 1).check(v0)).code)
    }

    @Test
    fun `once the hub has spoken 1 the gate remembers it`() {
        val gate = gate()
        gate.check(request())
        assertEquals(1, gate.highestSeen)
    }

    @Test
    fun `a Host other than the phone's own address and port is refused`() {
        val r = refused(gate().check(request(headers = arrayOf("Host" to "phone-a:8812", "Claude-Voice-Protocol" to "1", "Authorization" to "Bearer $key"))))
        assertEquals(403, r.status)
        assertEquals("unauthorized", r.code)
    }

    @Test
    fun `a request with an Origin, as from a web page, is refused`() {
        val fromPage = request(
            headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "1", "Authorization" to "Bearer $key", "Origin" to "http://100.64.0.9"),
        )
        assertEquals(403, refused(gate().check(fromPage)).status)
    }

    @Test
    fun `a caller off the tailnet is refused as such`() {
        val r = refused(gate().check(request(from = InetAddress.getByName("192.0.2.10"))))
        assertEquals(403, r.status)
        assertEquals("not_on_tailnet", r.code)
    }

    @Test
    fun `a wrong key is refused as not the paired hub, asking for the key`() {
        val r = refused(gate().check(request(headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "1", "Authorization" to "Bearer wrong"))))
        assertEquals(401, r.status)
        assertEquals("unauthorized", r.code)
        assertEquals("Bearer", r.headers["WWW-Authenticate"])
        assertEquals("1", r.headers["Claude-Voice-Protocol"])
    }

    @Test
    fun `no key at all is refused the same way`() {
        val r = refused(gate().check(request(headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "1"))))
        assertEquals(401, r.status)
    }

    @Test
    fun `the right key from another tailnet address names the next step`() {
        val r = refused(gate().check(request(from = InetAddress.getByName("100.64.0.7"))))
        assertEquals(401, r.status)
        assertEquals("unauthorized", r.code)
        assertTrue(r.error.contains("pair again"), r.error)
        assertTrue(r.error.contains("claude-voice machines approve phone-a"), r.error)
    }

    @Test
    fun `a phone not paired yet refuses every call and says how to pair`() {
        val r = refused(gate(hub = null).check(request()))
        assertEquals(401, r.status)
        assertTrue(r.error.contains("claude-voice machines approve phone-a"), r.error)
    }

    @Test
    fun `pairing needs no key, only the network and version checks`() {
        val pair = request(path = "/pair", headers = arrayOf("Host" to "100.64.0.2:8812", "Claude-Voice-Protocol" to "1"))
        assertEquals(GateDecision.Pass(version = 1), gate(hub = null).check(pair))
    }

    @Test
    fun `a version 0 refusal carries no code and no version header`() {
        val r = refused(gate().check(request(headers = arrayOf("Host" to "100.64.0.2:8812"))))
        assertEquals(null, r.code)
        assertEquals(null, r.headers["Claude-Voice-Protocol"])
    }

    @Test
    fun `on an IPv6 tailnet address the Host is the bracketed address, in any written form`() {
        val v6 = Gate(own = InetAddress.getByName("fd7a:115c:a1e0::2"), port = 8812, machine = "phone-a", hub = paired)
        fun host(value: String) =
            request(headers = arrayOf("Host" to value, "Claude-Voice-Protocol" to "1", "Authorization" to "Bearer $key"))
        assertEquals(GateDecision.Pass(version = 1), v6.check(host("[fd7a:115c:a1e0::2]:8812")))
        assertEquals(GateDecision.Pass(version = 1), v6.check(host("[FD7A:115C:A1E0:0:0:0:0:2]:8812")))
        assertEquals(403, refused(v6.check(host("fd7a:115c:a1e0::2:8812"))).status)
        assertEquals(403, refused(v6.check(host("[fd7a:115c:a1e0::3]:8812"))).status)
        assertEquals(403, refused(v6.check(host("[fd7a:115c:a1e0::2]:8813"))).status)
    }

    @Test
    fun `a Host that is a name is refused without being looked up`() {
        assertEquals(403, refused(gate().check(request(headers = arrayOf("Host" to "localhost:8812")))).status)
    }

    @Test
    fun `a Host that is not a valid address is refused, not thrown`() {
        val v6 = Gate(own = InetAddress.getByName("fd7a:115c:a1e0::2"), port = 8812, machine = "phone-a", hub = paired)
        assertEquals(403, refused(v6.check(request(headers = arrayOf("Host" to "[:::]:8812")))).status)
        assertEquals(403, refused(gate().check(request(headers = arrayOf("Host" to "100.64.0.256:8812")))).status)
        assertEquals(403, refused(gate().check(request(headers = arrayOf("Host" to "[fd7a:115c:a1e0::2]:8812")))).status)
    }
}
