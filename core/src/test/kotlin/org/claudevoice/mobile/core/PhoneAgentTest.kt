package org.claudevoice.mobile.core

import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The agent built in one place: one log, one pairing and one set of kept call ids for its life. */
class PhoneAgentTest {
    private val phone = InetAddress.getByName("100.64.0.2")
    private val moved = InetAddress.getByName("100.64.0.3")
    private val hubAt = InetAddress.getByName("100.64.0.1")

    private class Memory : HubStore {
        var text: String? = null

        override fun read() = text

        override fun write(text: String) {
            this.text = text
        }
    }

    private class LogMemory : LogStore {
        val kept = mutableListOf<String>()

        override fun append(line: String) {
            kept += line
        }

        override fun lines() = kept.toList()

        override fun replace(lines: List<String>) {
            kept.clear()
            kept += lines
        }
    }

    private val input = Json.parseToJsonElement("""{"type":"object"}""").jsonObject

    private val held = object : Plugin {
        override val name = "held"
        override val uses = listOf<String>()
        override val confirms = listOf<String>()
        override val tools = listOf(Tool("held_wait", "", input), Tool("held_now", "", input))

        override suspend fun call(tool: String, arguments: JsonObject): ToolResult =
            if (tool == "held_now") ToolResult.Answer(JsonObject(emptyMap())) else suspendCoroutine { }
    }

    private val store = Memory()
    private val log = CallLog(LogMemory(), clock = { Instant.parse("2026-11-17T12:00:00Z") })
    private val served = mutableListOf<Pair<BindAddress, Handler>>()
    private val admits = mutableListOf<(InetAddress) -> Boolean>()
    private val stopped = mutableListOf<InetAddress>()

    private val describe = { place: AgentPlace ->
        AgentInfo(Plugins(listOf(held)), "0.1.0", "phone-a", SettingsKeeper().settings, place)
    }

    private val agent = PhoneAgent(
        store,
        log,
        nameOf = { "laptop-a" },
        describe = describe,
        parts = PhoneAgent.Parts(
            pairing = Pairing(clock = { Instant.EPOCH }, random = SecureRandom()),
            port = 8812,
            callLimit = Duration.ofMillis(200),
            serve = { handler, admit, address, _ ->
                served += address to handler
                admits += admit
                Server { stopped += address.address }
            },
        ),
    )

    private fun handler(): Handler = served.last().second

    private fun request(target: String, body: String, at: InetAddress, key: String? = null) = ReadResult.Request(
        "POST",
        target,
        listOfNotNull(
            "host" to "${at.hostAddress}:8812",
            "claude-voice-protocol" to "1",
            "content-type" to "application/json",
            key?.let { "authorization" to "Bearer $it" },
        ).toMap(),
        body.toByteArray(),
    )

    private fun paired(at: InetAddress): String {
        val answer = handler().handle(request("/pair", """{"code":"${agent.pairing.newCode()}"}""", at), hubAt)
        assertEquals(200, answer.status, String(answer.body))
        return Json.parseToJsonElement(String(answer.body)).jsonObject["key"]!!.jsonPrimitive.content
    }

    private fun call(tool: String, id: String, key: String, at: InetAddress): JsonObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"$tool","arguments":{},"_meta":{"claude-voice/call_id":"$id"}}}"""
        val answer = handler().handle(request("/mcp", body, at, key), hubAt)
        assertEquals(200, answer.status, String(answer.body))
        return Json.parseToJsonElement(String(answer.body)).jsonObject["result"]!!.jsonObject
    }

    private fun outcomes() = agent.log.entries().map { it["outcome"]!!.jsonPrimitive.content }

    @Test
    fun `a call the hub makes is in the log the app's screen reads`() {
        agent.follow(TailnetState.OnTailnet(phone))
        val key = paired(phone)
        call("held_now", "c-1", key, phone)
        assertTrue(agent.log === log)
        assertEquals(listOf("held_now"), log.entries().map { it["tool"]!!.jsonPrimitive.content })
        assertEquals("laptop-a", log.entries().single()["hub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a late call retried after the tailnet dropped and came back is a duplicate, never run twice`() {
        agent.follow(TailnetState.OnTailnet(phone))
        val key = paired(phone)
        call("held_wait", "c-9", key, phone)
        agent.follow(TailnetState.TailscaleOff)
        agent.follow(TailnetState.OnTailnet(moved))
        val again = call("held_wait", "c-9", key, moved)
        assertEquals("duplicate_call", again["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("late", "duplicate"), outcomes())
    }

    @Test
    fun `it listens on the tailnet address, moves when it changes, and the old server stops first`() {
        agent.follow(TailnetState.OnTailnet(phone))
        assertEquals(ListenStatus.Listening(phone, 8812), agent.status)
        agent.follow(TailnetState.OnTailnet(moved))
        assertEquals(listOf(phone, moved), served.map { it.first.address })
        assertEquals(listOf(phone), stopped)
        agent.stop()
        assertEquals(listOf(phone, moved), stopped)
    }

    @Test
    fun `agent_info gives the bare tailnet address and the paired hub`() {
        agent.follow(TailnetState.OnTailnet(phone))
        val key = paired(phone)
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"agent_info","arguments":{}}}"""
        val answer = handler().handle(request("/mcp", body, phone, key), hubAt)
        val info = Json.parseToJsonElement(String(answer.body)).jsonObject["result"]!!.jsonObject["structuredContent"]!!.jsonObject
        assertEquals("100.64.0.2", info["address"]!!.jsonPrimitive.content)
        assertEquals("laptop-a", info["hub"]!!.jsonPrimitive.content)
        assertEquals("phone-a", info["machine"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pairing kept before is used at once, and nothing kept is no problem`() {
        assertNull(agent.problem)
        agent.follow(TailnetState.OnTailnet(phone))
        val key = paired(phone)
        val later = PhoneAgent(
            store,
            log,
            { "laptop-a" },
            describe,
            PhoneAgent.Parts(serve = { handler, _, address, _ ->
                served += address to handler
                Server { }
            }),
        )
        later.follow(TailnetState.OnTailnet(phone))
        call("held_now", "c-2", key, phone)
        assertNull(later.problem)
    }

    @Test
    fun `the port is protocol version 1's`() {
        assertEquals(8812, PhoneAgent.PORT)
    }

    @Test
    fun `the server lets in tailnet callers only, and once paired the hub alone`() {
        agent.follow(TailnetState.OnTailnet(phone))
        val admit = admits.single()
        assertTrue(admit(hubAt))
        assertFalse(admit(InetAddress.getByName("192.168.1.5")))
        paired(phone)
        assertFalse(admit(InetAddress.getByName("100.64.0.9")))
    }

    @Test
    fun `before pairing, the hub is told the phone's own machine name to approve`() {
        agent.follow(TailnetState.OnTailnet(phone))
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val answer = handler().handle(request("/mcp", body, phone), hubAt)
        assertEquals(401, answer.status)
        assertTrue("approve phone-a" in String(answer.body), String(answer.body))
    }

    @Test
    fun `with the app's own parts, an address the phone cannot bind says why instead of failing`() {
        store.text = "not a pairing"
        val real = PhoneAgent(store, log, { null }, describe)
        real.follow(TailnetState.OnTailnet(InetAddress.getByName("100.100.100.101")))
        assertNull(real.problem, "a phone that listens nowhere reports no gate")
        val status = real.status
        assertTrue(status is ListenStatus.Stopped && "could not listen on 100.100.100.101:8812" in status.reason, "$status")
    }

    @Test
    fun `the real server listens, turns away a caller off the tailnet, and closes when the agent stops`() {
        var socket: ServerSocket? = null
        val real = PhoneAgent(
            store,
            log,
            { null },
            describe,
            PhoneAgent.Parts(serve = PhoneAgent.serving { _, _ -> ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { socket = it } }),
        )
        real.follow(TailnetState.OnTailnet(phone))
        assertEquals(ListenStatus.Listening(phone, 8812), real.status)
        val port = socket!!.localPort
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.soTimeout = 5_000
            assertEquals(-1, it.getInputStream().read(), "a caller off the tailnet gets no answer")
        }
        real.stop()
        assertThrows<ConnectException> { Socket(InetAddress.getLoopbackAddress(), port).close() }
    }

    @Test
    fun `once it listens nowhere, the agent no longer reports the gate of the old address`() {
        store.text = "not a pairing"
        agent.follow(TailnetState.OnTailnet(phone))
        assertTrue(agent.problem != null, "a kept pairing that cannot be read is a problem while listening")
        agent.follow(TailnetState.TailscaleOff)
        assertNull(agent.problem)
        agent.follow(TailnetState.OnTailnet(phone))
        assertTrue(agent.problem != null)
        agent.stop()
        assertNull(agent.problem)
    }
}
