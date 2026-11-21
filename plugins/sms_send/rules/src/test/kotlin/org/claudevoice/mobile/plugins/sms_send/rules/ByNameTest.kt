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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Texting a contact by name, through the tools, as the hub calls them. */
class ByNameTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private var book = listOf(
        Contact("Ola Nordmann", "+4700000056", "mobile"),
        Contact("Kari Nordmann", "+4700000012", "mobile"),
        Contact("Kari Nordmann", "+4700000034", "home"),
        Contact("Per Berg", "+4700000078", "mobile", guessed = "+47"),
    )
    private var lookups = 0
    private val timers = mutableListOf<Runnable>()
    private val sent = mutableListOf<Pair<String, String>>()
    private var next = 0
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.parse("2026-11-21T07:30:00Z")

        override fun getZone(): ZoneId = ZoneId.of("Europe/Oslo")

        override fun withZone(zone: ZoneId) = this
    }
    private val outbox = Outbox(clock, { _, task -> timers += task }, { to, text -> sent += to to text }, {}, { "t${++next}" })
    private val recipients = Recipients(clock, { "p${++next}" }) { matches ->
        lookups += 1
        book.filter { matches(it.name) }
    }

    private fun call(tool: String, arguments: String) =
        SmsSend.call(outbox, tool, Json.parseToJsonElement(arguments).jsonObject, true, recipients)

    private fun content(reply: Reply): JsonObject = (reply as Reply.Answer).content

    private fun field(reply: Reply, key: String) = content(reply).getValue(key).jsonPrimitive.content

    private fun confirmed(reply: Reply): Reply =
        call("sms_send_confirm", """{"token":"${field(reply, "token")}","version":${field(reply, "version")}}""")

    @Test
    fun `the read-back of a draft by name names the contact and the whole number`() {
        val reply = call("sms_send_request", """{"to":"Ola","text":"Hei"}""")
        assertEquals("Draft to Ola Nordmann, +4700000056: 'Hei'", field(reply, "say"))
    }

    @Test
    fun `the answer to a confirm names the contact and the whole number too`() {
        val reply = confirmed(call("sms_send_request", """{"to":"Ola","text":"Hei"}"""))
        assertEquals(
            "Sending to Ola Nordmann, +4700000056 in 10 seconds. Say stop to cancel. I can't yet confirm delivery.",
            field(reply, "say"),
        )
    }

    @Test
    fun `a request whose text is refused still closes the open choices`() {
        val choices = content(call("sms_send_request", """{"to":"Kari","text":"Hei"}"""))
        val pick = choices.getValue("choices").jsonArray[0].jsonObject.getValue("pick").jsonPrimitive.content
        val refused = call("sms_send_request", """{"to":"$pick","text":"Se pay.click"}""") as Reply.Refused
        assertEquals(Limits.TEXT_REFUSED, refused.code)
        val stale = call("sms_send_request", """{"to":"$pick","text":"Hei"}""") as Reply.Refused
        assertEquals(Recipients.UNKNOWN_PICK, stale.code)
    }

    @Test
    fun `the read-back of a draft by number is as before`() {
        val reply = call("sms_send_request", """{"to":"+4700000001","text":"Hei"}""")
        assertEquals("Draft to +4700000001: 'Hei'", field(reply, "say"))
    }

    @Test
    fun `a pick comes back in to, and drafts that number`() {
        val choices = content(call("sms_send_request", """{"to":"Kari","text":"Hei"}"""))
        assertFalse(choices.containsKey("token"))
        val pick = choices.getValue("choices").jsonArray[1].jsonObject.getValue("pick").jsonPrimitive.content
        val draft = call("sms_send_request", """{"to":"$pick","text":"Hei"}""")
        assertEquals("Draft to Kari Nordmann, +4700000034: 'Hei'", field(draft, "say"))
    }

    @Test
    fun `a guessed number is offered as a choice, and its draft reads the whole number back`() {
        val choices = content(call("sms_send_request", """{"to":"Per","text":"Hei"}"""))
        val item = choices.getValue("choices").jsonArray.single().jsonObject
        assertEquals("Per Berg, mobile ending 78, guessed +47", item.getValue("say").jsonPrimitive.content)
        val draft = call("sms_send_request", """{"to":"${item.getValue("pick").jsonPrimitive.content}","text":"Hei"}""")
        assertEquals("Draft to Per Berg, +4700000078: 'Hei'", field(draft, "say"))
    }

    @Test
    fun `the draft keeps the number, so a contact changed later does not move the text`() {
        val draft = call("sms_send_request", """{"to":"Ola","text":"Hei"}""")
        book = listOf(Contact("Ola Nordmann", "+4700000099", "mobile"))
        confirmed(draft)
        timers.removeAt(0).run()
        assertEquals(listOf("+4700000056" to "Hei"), sent)
    }

    @Test
    fun `a text the rules refuse is refused before any lookup`() {
        val reply = call("sms_send_request", """{"to":"Ola","text":"Se pay.click"}""") as Reply.Refused
        assertEquals(Limits.TEXT_REFUSED, reply.code)
        val empty = call("sms_send_request", """{"to":"Ola","text":""}""") as Reply.Refused
        assertEquals("The text is empty; say what it should say.", empty.say)
        assertEquals(0, lookups)
    }

    @Test
    fun `the send limits hold for a number found by name`() {
        repeat(3) { confirmed(call("sms_send_request", """{"to":"+4700000056","text":"Hei $it"}""")) }
        val reply = call("sms_send_request", """{"to":"Ola","text":"Hei"}""") as Reply.Refused
        assertEquals(Limits.RATE_LIMITED, reply.code)
    }

    @Test
    fun `a number that is no number in full international form is refused as before`() {
        val reply = call("sms_send_request", """{"to":"900 00 001","text":"Hei"}""") as Reply.Refused
        assertEquals(Outbox.INVALID_RECIPIENT, reply.code)
        assertEquals(0, lookups)
    }

    @Test
    fun `the request tool takes a name, a number or a pick in to, and says how choices come back`() {
        val spec = SmsSend.TOOLS.single { it.name == SmsSend.REQUEST }
        val to = spec.input.getValue("properties").jsonObject.getValue("to").jsonObject
        assertFalse(to.containsKey("pattern"))
        assertEquals("1", to.getValue("minLength").jsonPrimitive.content)
        for (words in listOf("name", "pick", "choices", "spoken yes")) {
            assertTrue(spec.description.contains(words), words)
        }
    }
}
