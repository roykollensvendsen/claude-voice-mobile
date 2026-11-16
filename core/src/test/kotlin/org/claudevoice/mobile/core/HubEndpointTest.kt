package org.claudevoice.mobile.core

import java.net.InetAddress
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The hub's two routes, `/pair` and `/mcp`, behind the gate; and who the server lets in. */
class HubEndpointTest {
    private val phone = InetAddress.getByName("100.64.0.2")
    private val hubAt = InetAddress.getByName("100.64.0.1")
    private val other = InetAddress.getByName("100.64.0.9")
    private var now = Instant.EPOCH

    private class Memory : HubStore {
        var text: String? = null
        var failing = false

        override fun read() = text

        override fun write(text: String) {
            if (failing) throw java.io.IOException("disk full")
            this.text = text
        }
    }

    private val store = Memory()
    private val kept = KeptGate(store, own = phone, port = 8812, machine = "phone-a")
    private val pairing = Pairing(clock = { now }, random = SecureRandom())
    private val called = mutableListOf<InetAddress>()
    private val endpoint = HubEndpoint(kept, pairing, nameOf = { "laptop-a" }) { _, peer ->
        called += peer
        Answer(200, """{"jsonrpc":"2.0"}""".toByteArray())
    }

    private fun request(target: String, body: String = "", vararg headers: Pair<String, String>) =
        ReadResult.Request(
            "POST",
            target,
            (listOf("host" to "100.64.0.2:8812", "claude-voice-protocol" to "1") + headers)
                .associate { (name, value) -> name.lowercase() to value },
            body.toByteArray(),
        )

    private fun json(answer: Answer): JsonObject = Json.parseToJsonElement(String(answer.body)).jsonObject

    private fun field(answer: Answer, name: String) = json(answer)[name]?.jsonPrimitive?.content

    private fun pair(code: String, from: InetAddress = hubAt) =
        endpoint.handle(request("/pair", """{"code":"$code"}"""), from)

    @Test
    fun `the right code gives the hub its key and the hub's name, in version 1`() {
        val answer = pair(pairing.newCode())
        assertEquals(200, answer.status)
        assertEquals("laptop-a", field(answer, "hub"))
        assertTrue(Regex("^[A-Za-z0-9_-]{43,}$").matches(field(answer, "key")!!))
        assertEquals("1", answer.headers["Claude-Voice-Protocol"])
        assertEquals("laptop-a", kept.hubName)
    }

    @Test
    fun `after pairing, the hub's key from the hub's address reaches MCP`() {
        val key = field(pair(pairing.newCode()), "key")!!
        val answer = endpoint.handle(request("/mcp", "{}", "Authorization" to "Bearer $key"), hubAt)
        assertEquals(200, answer.status)
        assertEquals("1", answer.headers["Claude-Voice-Protocol"])
        assertEquals(listOf(hubAt), called)
    }

    @Test
    fun `a wrong code is 403 wrong_code, and so is a body with no code`() {
        pairing.newCode()
        for (body in listOf("""{"code":"AAAAAAAA"}""", "{}", "not json", """{"code":5}""", "[]")) {
            val answer = endpoint.handle(request("/pair", body), hubAt)
            assertEquals(403, answer.status, body)
            assertEquals("wrong_code", field(answer, "code"), body)
            assertEquals("1", answer.headers["Claude-Voice-Protocol"], body)
        }
        assertNull(kept.hubName)
    }

    @Test
    fun `the gate's refusal goes out with its code, versions and headers`() {
        val noVersion = endpoint.handle(request("/mcp", "{}").let {
            ReadResult.Request("POST", "/mcp", mapOf("host" to "100.64.0.2:8812", "claude-voice-protocol" to "2"), it.body)
        }, hubAt)
        assertEquals(400, noVersion.status)
        assertEquals("unsupported_version", field(noVersion, "code"))
        assertEquals("[1]", json(noVersion)["versions"].toString())

        val noKey = endpoint.handle(request("/mcp", "{}"), hubAt)
        assertEquals(401, noKey.status)
        assertEquals("unauthorized", field(noKey, "code"))
        assertEquals("Bearer", noKey.headers["WWW-Authenticate"])
        assertTrue(called.isEmpty())
    }

    @Test
    fun `a pairing the phone cannot keep is not given to the hub`() {
        store.failing = true
        val answer = pair(pairing.newCode())
        assertEquals(500, answer.status)
        assertNull(field(answer, "key"))
        assertTrue(field(answer, "error")!!.contains("pair again"))
        assertNull(kept.hubName)
    }

    @Test
    fun `before pairing, any tailnet address is let in`() {
        assertTrue(endpoint.admits(hubAt))
        assertTrue(endpoint.admits(other))
    }

    @Test
    fun `once paired, only the hub's address is let in`() {
        pair(pairing.newCode())
        assertTrue(endpoint.admits(hubAt))
        assertFalse(endpoint.admits(other))
    }

    @Test
    fun `while a new code is open, any tailnet address may reach pairing, until it ends`() {
        pair(pairing.newCode())
        pairing.newCode()
        assertTrue(endpoint.admits(other))
        now += Duration.ofMinutes(10)
        assertFalse(endpoint.admits(other))
    }

    @Test
    fun `a code used up by pairing closes the door again`() {
        pair(pairing.newCode())
        assertFalse(endpoint.admits(other))
    }

    @Test
    fun `before pairing, an address off the tailnet is not let in`() {
        assertFalse(endpoint.admits(InetAddress.getByName("192.168.1.20")))
        assertFalse(endpoint.admits(InetAddress.getByName("100.128.0.1")))
    }

    @Test
    fun `a name lookup that hangs is cut off, and answered as no name`() {
        val stuck = java.util.concurrent.CountDownLatch(1)
        val slow = HubEndpoint(kept, pairing, nameOf = { stuck.await(); "laptop-a" }, lookupLimit = Duration.ofMillis(100)) { _, _ ->
            Answer(200, "{}".toByteArray())
        }
        val started = System.nanoTime()
        val answer = slow.handle(request("/pair", """{"code":"${pairing.newCode()}"}"""), hubAt)
        stuck.countDown()
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "cut off in time")
        assertEquals(403, answer.status)
        assertEquals("not_on_tailnet", field(answer, "code"))
        assertTrue(field(answer, "error")!!.contains("MagicDNS"))
        assertNull(kept.hubName)
    }

    @Test
    fun `the name lookup runs on a daemon thread of its own, and a stalled one is interrupted`() {
        val ran = java.util.concurrent.ArrayBlockingQueue<Thread>(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val slow = HubEndpoint(
            kept,
            pairing,
            nameOf = {
                ran.add(Thread.currentThread())
                try {
                    Thread.sleep(60_000)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
                null
            },
            lookupLimit = Duration.ofMillis(100),
        ) { _, _ -> Answer(200, "{}".toByteArray()) }
        slow.handle(request("/pair", """{"code":"${pairing.newCode()}"}"""), hubAt)
        val thread = ran.take()
        assertTrue(thread.isDaemon, "a daemon thread")
        assertTrue(thread.name.startsWith("name-lookup"), thread.name)
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS), "the stalled lookup was interrupted")
    }

    @Test
    fun `an interrupted connection ends its wait as no name, and keeps its interrupt flag`() {
        // The lookup waits to be released: a lookup already done is returned without a look at
        // the interrupt, which made this test pass or fail by which thread ran first.
        val release = java.util.concurrent.CountDownLatch(1)
        val waiting = HubEndpoint(kept, pairing, nameOf = { release.await(); "laptop-a" }) { _, _ ->
            Answer(200, "{}".toByteArray())
        }
        val code = pairing.newCode()
        Thread.currentThread().interrupt()
        val answer = try {
            waiting.handle(request("/pair", """{"code":"$code"}"""), hubAt)
        } finally {
            assertTrue(Thread.interrupted(), "the flag is kept, and cleared here")
            release.countDown()
        }
        assertEquals(403, answer.status)
        assertTrue(field(answer, "error")!!.contains("MagicDNS"))
        assertNull(kept.hubName)
    }
}
