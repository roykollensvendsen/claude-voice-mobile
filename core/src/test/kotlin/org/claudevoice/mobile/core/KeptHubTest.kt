package org.claudevoice.mobile.core

import java.net.InetAddress
import java.time.Instant
import java.security.SecureRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeptHubTest {
    private val phone = InetAddress.getByName("100.64.0.2")
    private val hubAt = InetAddress.getByName("100.64.0.1")

    private class Memory : HubStore {
        var text: String? = null

        override fun read() = text

        override fun write(text: String) {
            this.text = text
        }
    }

    private fun paired(): PairResult.Paired {
        val pairing = Pairing(clock = { Instant.EPOCH }, random = SecureRandom())
        return pairing.pair(pairing.newCode(), hubAt) { "laptop-a" } as PairResult.Paired
    }

    private fun request(key: String, version: String? = "1", path: String = "/mcp") = GateRequest(
        path = path,
        peer = hubAt,
        headers = listOfNotNull(
            "Host" to "100.64.0.2:8812",
            "Authorization" to "Bearer $key",
            version?.let { "Claude-Voice-Protocol" to it },
        ).toMap(),
    )

    private fun start(store: HubStore) = KeptGate(store, own = phone, port = 8812, machine = "phone-a")

    @Test
    fun `a paired hub is still known after a restart`() {
        val store = Memory()
        val paired = paired()
        start(store).paired(paired)
        assertEquals(GateDecision.Pass(1, Route.MCP), start(store).gate.check(request(paired.key)))
        assertEquals("laptop-a", start(store).hubName)
    }

    @Test
    fun `after a restart a hub that has spoken version 1 cannot slip back to 0`() {
        val store = Memory()
        val paired = paired()
        val before = start(store).also { it.paired(paired) }
        assertEquals(GateDecision.Pass(0, Route.MCP), before.gate.check(request(paired.key, version = null)))
        before.gate.check(request(paired.key))
        val after = start(store)
        val refusal = after.gate.check(request(paired.key, version = null)) as GateDecision.Refuse
        assertEquals("unsupported_version", refusal.code)
    }

    @Test
    fun `what is kept holds no key, only its hash`() {
        val store = Memory()
        val paired = paired()
        start(store).paired(paired)
        assertTrue(paired.key !in checkNotNull(store.text))
    }

    @Test
    fun `pairing again replaces the hub, and the old key stops working`() {
        val store = Memory()
        val first = paired()
        val kept = start(store).also { it.paired(first) }
        kept.gate.check(request(first.key))
        val pairing = Pairing(clock = { Instant.EPOCH }, random = SecureRandom())
        val second = pairing.pair(pairing.newCode(), hubAt) { "laptop-b" } as PairResult.Paired
        kept.paired(second)
        assertEquals(401, (kept.gate.check(request(first.key)) as GateDecision.Refuse).status)
        assertEquals(GateDecision.Pass(1, Route.MCP), start(store).gate.check(request(second.key)))
        assertEquals("laptop-b", start(store).hubName)
    }

    @Test
    fun `nothing kept, or something unreadable, is a phone not paired`() {
        assertNull(start(Memory()).hubName)
        for (broken in listOf("", "{", "[]", """{"name":"a"}""", """{"key_hash":"!!","name":"a","address":"ZEAAAQ==","highest":1}""")) {
            val store = Memory().apply { text = broken }
            assertNull(start(store).hubName, broken)
            assertEquals(401, (start(store).gate.check(request("x")) as GateDecision.Refuse).status, broken)
        }
    }

    @Test
    fun `a kept address off the tailnet is not trusted`() {
        val store = Memory()
        start(store).paired(paired())
        store.text = store.text!!.replace(Regex(""""address":"[^"]+""""), """"address":"wAACCg=="""")
        assertNull(start(store).hubName)
    }
}
