package org.claudevoice.mobile.core

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpHandlerTest {
    private val hub = InetAddress.getByName("100.64.0.1")

    private val battery = Tool(
        "status_battery",
        "How full the phone's battery is.",
        buildJsonObject { put("type", "object") },
    )
    private val ring = Tool(
        "status_ring",
        "Rings the phone so it can be found.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("seconds") { put("type", "integer") } }
        },
    )

    private val runs = AtomicInteger()
    private val released = CountDownLatch(1)
    private val blocking = CountDownLatch(4)
    private val unblocked = CountDownLatch(1)
    private val blocks = AtomicInteger()
    private val failures = mutableListOf<String>()
    private var now = 0L

    private val status = object : Plugin {
        override val name = "status"
        override val tools = listOf("hang", "break", "later", "later_break", "slow", "block").map { Tool("status_$it", "", battery.input) }
            .let { listOf(battery, ring) + it }

        override suspend fun call(tool: String, arguments: JsonObject): ToolResult {
            runs.incrementAndGet()
            return when (tool) {
                "status_battery" -> ToolResult.Answer(buildJsonObject { put("percent", 81) })
                "status_block" -> {
                    // Holds its thread, as a plugin that blocks instead of suspending would.
                    blocks.incrementAndGet()
                    blocking.countDown()
                    unblocked.await(5, TimeUnit.SECONDS)
                    ToolResult.Answer(JsonObject(emptyMap()))
                }
                "status_hang" -> {
                    released.await(5, TimeUnit.SECONDS)
                    ToolResult.Answer(buildJsonObject { put("late", true) })
                }
                "status_break" -> throw IllegalStateException("a private detail")
                "status_later" -> later { it.resume(ToolResult.Answer(buildJsonObject { put("later", true) })) }
                "status_later_break" -> later { it.resumeWithException(IllegalArgumentException("private")) }
                "status_slow" -> later(waitMillis = 1_500) { it.resume(ToolResult.Answer(JsonObject(emptyMap()))) }
                else -> ToolResult.Refused("The phone is in silent mode, so it cannot ring.")
            }
        }
    }

    /** Suspends, and is resumed by another thread a moment later, as a plugin waiting on the phone would be. */
    private suspend fun later(
        waitMillis: Long = 50,
        resume: (kotlin.coroutines.Continuation<ToolResult>) -> Unit,
    ): ToolResult =
        suspendCoroutine { done ->
            thread {
                Thread.sleep(waitMillis)
                resume(done)
            }
        }

    private fun handler(limit: Duration = Duration.ofSeconds(5), keptIds: Int = 10_000) = McpHandler(
        Plugins(listOf(status)),
        softwareVersion = "0.1.0",
        clock = { now },
        callLimit = limit,
        keptIds = keptIds,
        failed = { failures.add(it) },
    )

    private val handler = handler()

    private fun post(body: String, to: McpHandler = handler) = to.handle(
        ReadResult.Request("POST", "/mcp", mapOf("content-type" to "application/json"), body.toByteArray()),
        hub,
    )

    private fun rpc(method: String, params: String = "{}", id: String = "1") =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    private fun call(tool: String, id: String? = null, arguments: String = "{}") = rpc(
        "tools/call",
        if (id == null) """{"name":"$tool","arguments":$arguments}"""
        else """{"name":"$tool","arguments":$arguments,"_meta":{"claude-voice/call_id":"$id"}}""",
    )

    private fun answered(body: String, to: McpHandler = handler): JsonObject {
        val answer = post(body, to)
        assertEquals(200, answer.status)
        return Json.parseToJsonElement(String(answer.body)).jsonObject
    }

    private fun result(body: String, to: McpHandler = handler): JsonObject = answered(body, to)["result"]!!.jsonObject

    private fun isError(result: JsonObject) = result["isError"]?.jsonPrimitive?.content.toBoolean()

    private fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun errorCode(body: String): Int = answered(body)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    @Test
    fun `initialize names the phone, offers tools, and keeps the protocol version the hub asked for`() {
        val reply = answered(rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{}}""", id = "7"))
        assertEquals(JsonPrimitive("2.0"), reply["jsonrpc"])
        assertEquals(JsonPrimitive(7), reply["id"])
        val result = reply["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
        val server = result["serverInfo"]!!.jsonObject
        assertEquals("claude-voice-mobile", server["name"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", server["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize answers with its own latest version when it does not know the hub's`() {
        val result = result(rpc("initialize", """{"protocolVersion":"1999-01-01","capabilities":{}}"""))
        assertEquals(McpHandler.LATEST, result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize keeps an older version it knows, and answers its latest when none is asked`() {
        val older = result(rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{}}"""))
        assertEquals("2025-03-26", older["protocolVersion"]!!.jsonPrimitive.content)
        for (params in listOf("{}", """{"protocolVersion":{}}""", "[]")) {
            val result = result(rpc("initialize", params))
            assertEquals(McpHandler.LATEST, result["protocolVersion"]!!.jsonPrimitive.content, params)
        }
    }

    @Test
    fun `an id that is a string comes back as the same string`() {
        assertEquals(JsonPrimitive("a-1"), answered(rpc("ping", id = "\"a-1\""))["id"])
    }

    @Test
    fun `ping answers an empty result`() {
        assertEquals(JsonObject(emptyMap()), result(rpc("ping")))
    }

    @Test
    fun `tools list offers every plugin's tools with their inputs`() {
        val tools = result(rpc("tools/list"))["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("status_battery", "status_ring", "status_hang", "status_break", "status_later", "status_later_break", "status_slow", "status_block"),
            tools.map { it["name"]!!.jsonPrimitive.content },
        )
        assertEquals(ring.input, tools[1]["inputSchema"])
        assertEquals(ring.description, tools[1]["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call answers the plugin's structured answer, and the same as text`() {
        val result = result(rpc("tools/call", """{"name":"status_battery","arguments":{}}"""))
        val answer = buildJsonObject { put("percent", 81) }
        assertEquals(answer, result["structuredContent"])
        val text = result["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals(answer, Json.parseToJsonElement(text["text"]!!.jsonPrimitive.content))
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun `a refusal is an error answer with the plugin's words`() {
        val result = result(rpc("tools/call", """{"name":"status_ring","arguments":{"seconds":5}}"""))
        assertTrue(isError(result))
        assertEquals("The phone is in silent mode, so it cannot ring.", text(result))
    }

    @Test
    fun `a call with no arguments runs with none`() {
        val result = result(rpc("tools/call", """{"name":"status_battery"}"""))
        assertEquals(buildJsonObject { put("percent", 81) }, result["structuredContent"])
    }

    @Test
    fun `a tool the phone does not offer is an error answer that names it`() {
        val result = result(rpc("tools/call", """{"name":"status_fly","arguments":{}}"""))
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("status_fly" in text, text)
    }

    @Test
    fun `a method the phone does not know is refused as not found, in words that name it`() {
        assertEquals(McpHandler.METHOD_NOT_FOUND, errorCode(rpc("sampling/createMessage")))
        val message = answered(rpc("sampling/createMessage"))["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue("sampling/createMessage" in message, message)
    }

    private fun refusedWith(body: String): Int {
        val answer = post(body)
        assertEquals(400, answer.status, body)
        val reply = Json.parseToJsonElement(String(answer.body)).jsonObject
        assertEquals(JsonPrimitive(null as String?), reply["id"])
        return reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
    }

    @Test
    fun `a body that is not JSON is refused with 400 and a parse error with no id`() {
        assertEquals(McpHandler.PARSE_ERROR, refusedWith("{not json"))
        assertEquals(McpHandler.PARSE_ERROR, refusedWith(""))
    }

    @Test
    fun `a batch, or a body that is no JSON-RPC message, is refused with 400`() {
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("[${rpc("ping")}]"))
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("\"ping\""))
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("""{"jsonrpc":"1.0","id":1,"method":"ping"}"""))
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("""{"jsonrpc":"2.0","id":1}"""))
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("""{"jsonrpc":"2.0","id":{},"method":"ping"}"""))
        assertEquals(McpHandler.INVALID_REQUEST, refusedWith("""{"jsonrpc":"2.0","id":true,"method":"ping"}"""))
    }

    @Test
    fun `a notification is accepted with 202 and an empty body`() {
        val answer = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, answer.status)
        assertEquals(0, answer.body.size)
    }

    @Test
    fun `a call with an id runs once, and its second send is refused as a duplicate`() {
        assertFalse(isError(result(call("status_battery", id = "c-1"))))
        val again = result(call("status_battery", id = "c-1"))
        assertTrue(isError(again))
        assertEquals("duplicate_call", again["structuredContent"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(again["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(1, runs.get())
    }

    @Test
    fun `calls without an id each run, as from a version 0 hub`() {
        result(call("status_battery"))
        result(call("status_battery"))
        assertEquals(2, runs.get())
    }

    @Test
    fun `an id is remembered for ten minutes and then may run again`() {
        result(call("status_battery", id = "c-2"))
        now = Duration.ofMinutes(10).toMillis() - 1
        assertTrue(isError(result(call("status_battery", id = "c-2"))))
        now = Duration.ofMinutes(10).toMillis()
        assertFalse(isError(result(call("status_battery", id = "c-2"))))
        assertEquals(2, runs.get())
    }

    @Test
    fun `a call that never ran keeps its id free for the retry`() {
        assertTrue(isError(result(call("status_fly", id = "c-3"))))
        assertEquals(McpHandler.INVALID_PARAMS, errorCode(call("status_battery", id = "c-3", arguments = "[]")))
        assertFalse(isError(result(call("status_battery", id = "c-3"))))
    }

    @Test
    fun `when the kept ids are full of young ones, a new call is refused and none is forgotten`() {
        val small = handler(keptIds = 2)
        result(call("status_battery", id = "a"), small)
        result(call("status_battery", id = "b"), small)
        val busy = result(call("status_battery", id = "c"), small)
        assertTrue(isError(busy))
        assertEquals(2, runs.get())
        assertTrue(isError(result(call("status_battery", id = "a"), small)), "a is still kept")
        now = Duration.ofMinutes(10).toMillis()
        assertFalse(isError(result(call("status_battery", id = "c"), small)), "old ones go first")
    }

    @Test
    fun `a call past the limit answers that it may still finish, and keeps its id`() {
        // A first call loads the classes and starts a thread, so the hang starts at once and the
        // limit catches it running, not still waiting for a thread.
        result(call("status_battery"))
        val quick = handler(limit = Duration.ofMillis(1_000))
        val late = result(call("status_hang", id = "h-1"), quick)
        assertTrue(isError(late))
        assertTrue("may still" in text(late), text(late))
        released.countDown()
        assertTrue(isError(result(call("status_hang", id = "h-1"), quick)), "the retry is refused")
    }

    @Test
    fun `a plugin that throws is an error answer, and only the exception's class reaches the log`() {
        val broke = result(call("status_break"))
        assertTrue(isError(broke))
        assertTrue("status_break" in text(broke), text(broke))
        assertFalse("private" in text(broke))
        assertEquals(listOf("java.lang.IllegalStateException"), failures)
    }

    @Test
    fun `a call whose name or arguments have the wrong shape is invalid params`() {
        assertEquals(McpHandler.INVALID_PARAMS, errorCode(rpc("tools/call", """{"arguments":{}}""")))
        assertEquals(McpHandler.INVALID_PARAMS, errorCode(rpc("tools/call", """{"name":"status_battery","arguments":[]}""")))
        assertEquals(McpHandler.INVALID_PARAMS, errorCode(rpc("tools/call", "[]")))
    }

    @Test
    fun `an answer says nothing of a session`() {
        assertNull(post(rpc("ping")).headers.keys.firstOrNull { it.lowercase() == "mcp-session-id" })
    }

    @Test
    fun `a plugin that suspends and is resumed elsewhere answers as usual, or as broken`() {
        assertEquals(buildJsonObject { put("later", true) }, result(call("status_later"))["structuredContent"])
        val broke = result(call("status_later_break"))
        assertTrue(isError(broke))
        assertEquals(listOf("java.lang.IllegalArgumentException"), failures)
    }

    @Test
    fun `suspended calls give their threads back, so more of them than threads all finish in time`() {
        // Eight calls of 1.5 s each under a 2.5 s limit, on four threads: held threads would leave the
        // second four to start at 1.5 s and end at 3 s, late. Suspended, all end near 1.5 s, with a
        // second to spare for a busy machine.
        val quick = handler(limit = Duration.ofMillis(2_500))
        val late = AtomicInteger()
        val answers = (1..8).map {
            thread { if (isError(result(call("status_slow"), quick))) late.incrementAndGet() }
        }
        answers.forEach { it.join(5_000) }
        assertEquals(0, late.get())
        assertEquals(8, runs.get())
    }

    @Test
    fun `an interrupted wait is answered as late or not run, and keeps the interrupt`() {
        Thread.currentThread().interrupt()
        val late = try {
            result(call("status_hang"))
        } finally {
            assertTrue(Thread.interrupted(), "the flag is kept, and cleared here")
            released.countDown()
        }
        // Interrupted before any thread took the call, it is cancelled; after, it may still finish.
        assertTrue("may still" in text(late) || "did not run" in text(late), text(late))
    }

    @Test
    fun `the default clock is in milliseconds and never goes back`() {
        val first = CallIds.monotonicMillis()
        Thread.sleep(20)
        val second = CallIds.monotonicMillis()
        assertTrue(second - first in 20L..2_000L, "${second - first}")
        McpHandler(Plugins(listOf(status)), "0.1.0").let { plain ->
            result(call("status_battery", id = "d"), plain)
            assertTrue(isError(result(call("status_battery", id = "d"), plain)))
        }
    }

    @Test
    fun `a call waiting behind four stuck ones is dropped unrun when its time is up, and its id stays free`() {
        val quick = handler(limit = Duration.ofMillis(100))
        val stuck = (1..4).map { thread { result(call("status_block"), quick) } }
        assertTrue(blocking.await(5, TimeUnit.SECONDS), "all four threads are held")
        val fifth = result(call("status_block", id = "q-5"), quick)
        assertTrue(isError(fifth))
        assertTrue("did not run" in text(fifth), text(fifth))
        unblocked.countDown()
        stuck.forEach { it.join(5_000) }
        Thread.sleep(300)
        assertEquals(4, blocks.get(), "the fifth never reached the plugin, even once a thread was free")
        assertFalse(isError(result(call("status_block", id = "q-5"), quick)), "its retry runs")
        assertEquals(5, blocks.get())
    }
}
