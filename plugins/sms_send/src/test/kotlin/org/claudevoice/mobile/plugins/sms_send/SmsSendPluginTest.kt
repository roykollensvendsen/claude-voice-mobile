package org.claudevoice.mobile.plugins.sms_send

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.pluginapi.ToolResult
import org.claudevoice.mobile.plugins.sms_send.rules.Contact
import org.claudevoice.mobile.plugins.sms_send.rules.Outbox
import org.claudevoice.mobile.plugins.sms_send.rules.Recipients
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSendPluginTest {
    private var permitted = true
    private val outbox = Outbox(
        clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
        later = { _, _ -> },
        send = { _, _ -> },
        failed = {},
        tokens = { "t1" },
    )
    // A made-up contact, with a number in the +47 00 range no one is given.
    private val recipients = Recipients(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")), { "p1" }) { matches, _ ->
        listOf(Contact("Ola Nordmann", "+4700000056", "mobile")).filter { matches(it.name) }
    }
    private val plugin = SmsSendPlugin(outbox, recipients) { permitted }

    /** Runs a call to its end; the plugin never suspends, so it ends at once. */
    private fun call(tool: String, arguments: String): ToolResult {
        var result: Result<ToolResult>? = null
        val parsed = Json.parseToJsonElement(arguments).jsonObject
        suspend { plugin.call(tool, parsed) }.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    @Test
    fun `it offers the three sending tools under its own name, and only confirm needs a yes`() {
        assertEquals("sms_send", plugin.name)
        assertEquals(
            listOf("sms_send_request", "sms_send_confirm", "sms_send_cancel"),
            plugin.tools.map { it.name },
        )
        assertEquals(listOf("messages", "contacts"), plugin.uses)
        assertEquals(listOf("sms_send_confirm"), plugin.confirms)
        assertEquals(setOf("to", "text"), plugin.tools[0].input["properties"]!!.jsonObject.keys)
    }

    @Test
    fun `an answer reaches the hub as the rules gave it`() {
        val answer = call("sms_send_request", """{"to":"+4700000001","text":"Hei"}""") as ToolResult.Answer
        assertEquals("Draft to +4700000001: 'Hei'", answer.content["say"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a name in to is looked up among the contacts`() {
        val answer = call("sms_send_request", """{"to":"Ola","text":"Hei"}""") as ToolResult.Answer
        assertEquals("Draft to Ola Nordmann, +4700000056: 'Hei'", answer.content["say"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refusal reaches the hub with its words and its code, and the permission is asked at each call`() {
        permitted = false
        assertEquals("permission_missing", (call("sms_send_cancel", """{"token":"t1"}""") as ToolResult.Refused).code)
        permitted = true
        assertEquals(
            ToolResult.Refused("There is no draft by that token waiting; make a new request.", Outbox.NO_DRAFT),
            call("sms_send_cancel", """{"token":"t1"}"""),
        )
    }
}
