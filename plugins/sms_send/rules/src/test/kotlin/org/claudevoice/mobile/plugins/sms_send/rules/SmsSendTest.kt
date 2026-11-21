package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSendTest {
    private val timers = mutableListOf<Runnable>()
    private val sent = mutableListOf<Pair<String, String>>()
    private val outbox = Outbox(
        clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
        later = { _, task -> timers += task },
        send = { to, text -> sent += to to text },
        failed = {},
        tokens = { "t1" },
    )

    private fun call(tool: String, arguments: String) =
        SmsSend.call(outbox, tool, Json.parseToJsonElement(arguments).jsonObject, permitted = true)

    private fun say(reply: Reply) = when (reply) {
        is Reply.Answer -> reply.content["say"]!!.jsonPrimitive.content
        is Reply.Refused -> reply.say
    }

    @Test
    fun `a request, its confirm and the timer go through the tools to the radio`() {
        assertEquals("Draft to +4700000001: 'Hei'", say(call(SmsSend.REQUEST, """{"to":"+4700000001","text":"Hei"}""")))
        assertTrue(say(call(SmsSend.CONFIRM, """{"token":"t1","version":1}""")).startsWith("Sending to +4700000001"))
        timers.single().run()
        assertEquals(listOf("+4700000001" to "Hei"), sent)
    }

    @Test
    fun `stop goes through the tools too`() {
        call(SmsSend.REQUEST, """{"to":"+4700000001","text":"Hei"}""")
        assertEquals("Stopped, not sent.", say(call(SmsSend.CANCEL, """{"token":"t1"}""")))
    }

    @Test
    fun `an argument of the wrong kind is refused in words, and nothing is kept or sent`() {
        val wrong = listOf(
            SmsSend.REQUEST to """{"to":4700000001,"text":"Hei"}""",
            SmsSend.REQUEST to """{"to":"+4700000001","text":["Hei"]}""",
            SmsSend.CONFIRM to """{"token":1,"version":1}""",
            SmsSend.CONFIRM to """{"token":"t1","version":"1"}""",
            SmsSend.CONFIRM to """{"token":"t1","version":1.5}""",
            SmsSend.CONFIRM to """{"token":"t1","version":null}""",
            SmsSend.CANCEL to """{"token":null}""",
        )
        for ((tool, arguments) in wrong) {
            val reply = call(tool, arguments)
            assertEquals(Reply.Refused("$tool was given an argument of the wrong kind."), reply, arguments)
        }
        assertTrue(timers.isEmpty() && sent.isEmpty())
        assertEquals(Outbox.NO_DRAFT, (call(SmsSend.CANCEL, """{"token":"t1"}""") as Reply.Refused).code)
    }

    @Test
    fun `a missing or extra argument is refused by naming the ones the tool takes`() {
        assertEquals(
            Reply.Refused("sms_send_request takes exactly to, text."),
            call(SmsSend.REQUEST, """{"to":"+4700000001"}"""),
        )
        assertEquals(
            Reply.Refused("sms_send_cancel takes exactly token."),
            call(SmsSend.CANCEL, """{"token":"t1","now":true}"""),
        )
    }

    @Test
    fun `a tool the plugin does not have is refused by name, even without the permission`() {
        val reply = SmsSend.call(outbox, "sms_send_later", JsonObject(emptyMap()), permitted = false)
        assertEquals(Reply.Refused("sms_send has no tool sms_send_later."), reply)
    }

    @Test
    fun `every tool says what it does, and requires every argument it takes and no other`() {
        for (tool in SmsSend.TOOLS) {
            assertTrue(tool.description.isNotBlank(), tool.name)
            assertEquals("object", tool.input["type"]!!.jsonPrimitive.content)
            assertEquals("false", tool.input["additionalProperties"]!!.jsonPrimitive.content)
            assertEquals(tool.keys, tool.input["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
        assertEquals(
            listOf(setOf("to", "text"), setOf("token", "version"), setOf("token")),
            SmsSend.TOOLS.map { it.keys },
        )
    }
}
