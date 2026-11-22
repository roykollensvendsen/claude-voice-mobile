package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One text to several recipients (ADR-CVM-010, "Several recipients", as #168 settled it). */
class SeveralRecipientsTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private val a = "+4700000001"
    private val b = "+4700000002"
    private val c = "+4700000003"
    private val kari = Contact("Kari Nordmann", "+4700000012", "mobile")
    private val kariHome = Contact("Kari Nordmann", "+4700000034", "home")
    private val ola = Contact("Ola Nordmann", "+4700000056", "mobile")
    private val olaWork = Contact("Ola Nordmann", "+4700000078", "work")
    private val per = Contact("Per Hansen", "+4700000090", "mobile")

    private inner class Phone {
        var now: Instant = Instant.parse("2026-11-21T07:30:00Z")
        val timers = mutableListOf<Runnable>()
        val sent = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        val refusing = mutableSetOf<String>()
        var lookups = 0
        private var next = 0
        private val clock = object : Clock() {
            override fun instant() = now

            override fun getZone(): ZoneId = ZoneId.of("Europe/Oslo")

            override fun withZone(zone: ZoneId) = this
        }
        val outbox = Outbox(
            clock = clock,
            later = { _, task -> timers += task },
            send = { number, text ->
                if (number in refusing) throw IllegalStateException("no default subscription")
                sent += number to text
            },
            failed = { failures += it },
            tokens = { "t${++next}" },
        )
        val recipients = Recipients(clock, tokens = { "p${++next}" }) { matches ->
            lookups += 1
            listOf(kari, kariHome, ola, olaWork, per).filter { matches(it.name) }
        }

        fun request(text: String, vararg to: String) = outbox.request(to.toList(), text, recipients)

        fun fire() = timers.removeAt(0).run()

        /** Sends [n] texts to distinct numbers, confirmed now, so they count toward the limits. */
        fun sendSome(n: Int) = repeat(n) { i ->
            val drafted = outbox.request(listOf("+47000001%02d".format(i)), "Hei")
            outbox.confirm(token(drafted), 1)
            fire()
        }
    }

    private fun content(reply: Reply): JsonObject = (reply as Reply.Answer).content

    private fun token(reply: Reply) = content(reply)["token"]!!.jsonPrimitive.content

    private fun version(reply: Reply) = content(reply)["version"]!!.jsonPrimitive.int

    private fun picks(reply: Reply) =
        content(reply).getValue("choices").jsonArray.map { it.jsonObject.getValue("pick").jsonPrimitive.content }

    private fun say(reply: Reply) = when (reply) {
        is Reply.Answer -> reply.content["say"]!!.jsonPrimitive.content
        is Reply.Refused -> reply.say
    }

    private fun code(reply: Reply) = (reply as Reply.Refused).code

    @Test
    fun `the read-back names every recipient with the whole number and gives the text once`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a, b, c)
        assertEquals("Draft to +4700000001; +4700000002; +4700000003: 'Kommer'", say(drafted))
        assertEquals(1, version(drafted))
        assertTrue(phone.sent.isEmpty() && phone.timers.isEmpty())
    }

    @Test
    fun `a list of one behaves as the string`() {
        val phone = Phone()
        assertEquals("Draft to +4700000001: 'Hei'", say(phone.request("Hei", a)))
    }

    @Test
    fun `the same number twice counts once, and keeps its first place`() {
        val phone = Phone()
        assertEquals("Draft to +4700000002; +4700000001: 'Hei'", say(phone.request("Hei", b, a, b)))
    }

    @Test
    fun `names are read back with their numbers, in the order given`() {
        val phone = Phone()
        val picked = picks(phone.request("Hei", "Kari"))
        val drafted = phone.request("Hei", "Per", picked[0])
        assertEquals("Draft to Per Hansen, +4700000090; Kari Nordmann, +4700000012: 'Hei'", say(drafted))
    }

    @Test
    fun `one yes and one undo delay send one text to each, in read-back order`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a, b, c)
        assertEquals(
            "Sending to +4700000001; +4700000002; +4700000003 in 10 seconds. Say stop to cancel. " +
                "I can't yet confirm delivery.",
            say(phone.outbox.confirm(token(drafted), 1)),
        )
        assertEquals(1, phone.timers.size)
        phone.fire()
        assertEquals(listOf(a to "Kommer", b to "Kommer", c to "Kommer"), phone.sent)
    }

    @Test
    fun `one cancel stops them all`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a, b)
        phone.outbox.confirm(token(drafted), 1)
        assertEquals("Stopped, not sent.", say(phone.outbox.cancel(token(drafted))))
        phone.fire()
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun `one recipient Android refuses never stops the next, and is logged`() {
        val phone = Phone()
        phone.refusing += b
        val drafted = phone.request("Kommer", a, b, c)
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(listOf(a to "Kommer", c to "Kommer"), phone.sent)
        assertEquals(listOf(Outbox.SEND_FAILED), phone.failures)
    }

    @Test
    fun `a cancel after the delay names how many were handed to Android, and each it refused`() {
        val phone = Phone()
        phone.refusing += b
        val drafted = phone.request("Kommer", a, b, c)
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(
            "Too late to stop; handed 2 of 3 to Android at 08.30; it refused the text to +4700000002. " +
                "I can't yet confirm delivery.",
            say(phone.outbox.cancel(token(drafted))),
        )
    }

    @Test
    fun `a cancel after the delay names refused recipients as the read-back named them, in its order`() {
        val phone = Phone()
        phone.refusing += per.number
        phone.refusing += a
        val picked = picks(phone.request("Hei", "Kari"))
        val drafted = phone.request("Hei", "Per", a, picked[0])
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(
            "Too late to stop; handed 1 of 3 to Android at 08.30; it refused the text to " +
                "Per Hansen, +4700000090 and +4700000001. I can't yet confirm delivery.",
            say(phone.outbox.cancel(token(drafted))),
        )
    }

    @Test
    fun `a cancel after the delay with every text handed names no refusal`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a, b)
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(
            "Too late to stop; handed 2 of 2 to Android at 08.30. I can't yet confirm delivery.",
            say(phone.outbox.cancel(token(drafted))),
        )
    }

    @Test
    fun `a cancel while the texts are being handed over waits, and hears the whole outcome`() {
        val phone = Phone()
        lateinit var outbox: Outbox
        var drafted: Reply? = null
        var heard: Reply? = null
        val canceller = Thread { heard = outbox.cancel(token(drafted!!)) }
        outbox = Outbox(
            clock = Clock.fixed(phone.now, ZoneId.of("Europe/Oslo")),
            later = { _, task -> phone.timers += task },
            send = { number, _ ->
                if (number == a) canceller.start()
                // The cancel must still be waiting for the lock when the next text goes.
                if (number == b) awaitBlockedOrDone(canceller)
                phone.sent += number to "Kommer"
            },
            failed = {},
            tokens = { "t1" },
        )
        drafted = outbox.request(listOf(a, b), "Kommer")
        outbox.confirm(token(drafted!!), 1)
        phone.fire()
        canceller.join()
        assertEquals(
            "Too late to stop; handed 2 of 2 to Android at 08.30. I can't yet confirm delivery.",
            say(heard!!),
        )
    }

    private fun awaitBlockedOrDone(thread: Thread) {
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) Thread.onSpinWait()
    }

    @Test
    fun `a cancel after the delay for one recipient keeps its words, as the first version does`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a)
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(
            "Too late to stop; it was handed to the phone at 08.30. I can't yet confirm delivery.",
            say(phone.outbox.cancel(token(drafted))),
        )
    }

    @Test
    fun `changing the list makes a new version with a new read-back, so an old yes is refused`() {
        val phone = Phone()
        val first = phone.request("Kommer", a, b)
        val second = phone.request("Kommer", a, b, c)
        assertEquals(token(first), token(second))
        assertEquals(2, version(second))
        assertEquals("Draft to +4700000001; +4700000002; +4700000003: 'Kommer'", say(second))
        assertEquals(Outbox.STALE_VERSION, code(phone.outbox.confirm(token(first), 1)))
        phone.outbox.confirm(token(second), 2)
        phone.fire()
        assertEquals(listOf(a, b, c), phone.sent.map { it.first })
    }

    @Test
    fun `a list sharing recipients with two held drafts becomes the oldest one's next version`() {
        val phone = Phone()
        val older = phone.request("Kommer", a)
        phone.request("Hei", b)
        val joined = phone.request("Kommer", a, b)
        assertEquals(token(older), token(joined))
        assertEquals(2, version(joined))
    }

    @Test
    fun `every other held draft sharing a recipient is stopped, so two yeses never send one person two texts`() {
        val phone = Phone()
        phone.request("Kommer", a)
        val newer = phone.request("Hei", b)
        val joined = phone.request("Kommer", a, b)
        assertEquals(
            "Draft to +4700000001; +4700000002: 'Kommer' (replaces 1 other held draft)",
            say(joined),
        )
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.confirm(token(newer), 1)))
        phone.outbox.confirm(token(joined), 2)
        phone.fire()
        assertEquals(listOf(a to "Kommer", b to "Kommer"), phone.sent)
    }

    @Test
    fun `the read-back counts every held draft it replaced, and leaves alone one that shares no one`() {
        val phone = Phone()
        phone.request("Kommer", a)
        phone.request("Hei", b)
        phone.request("Hallo", c)
        val apart = phone.request("Snart", "+4700000009")
        assertEquals(
            "Draft to +4700000001; +4700000002; +4700000003: 'Kommer' (replaces 2 other held drafts)",
            say(phone.request("Kommer", a, b, c)),
        )
        phone.outbox.confirm(token(apart), 1)
        phone.fire()
        assertEquals(listOf("+4700000009" to "Snart"), phone.sent)
    }

    @Test
    fun `a list with no recipient in common is a draft of its own`() {
        val phone = Phone()
        val first = phone.request("Kommer", a)
        val second = phone.request("Kommer", b, c)
        assertTrue(token(first) != token(second))
        assertEquals(1, version(second))
    }

    @Test
    fun `one recipient that is no number refuses the whole group, and nothing is drafted`() {
        val phone = Phone()
        val reply = phone.request("Kommer", a, "+47 12")
        assertEquals(Outbox.INVALID_RECIPIENT, code(reply))
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.cancel("t1")))
    }

    @Test
    fun `each recipient counts as one send against the limits`() {
        val phone = Phone()
        phone.sendSome(8)
        val drafted = phone.request("Kommer", a, b)
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        val after = phone.request("Kommer", c)
        assertEquals(Limits.RATE_LIMITED, code(after))
        assertEquals("Not drafted: that's 10 texts in the last hour; try again after 09.30.", say(after))
    }

    @Test
    fun `a group that does not fit is refused whole before any draft, saying how many would fit`() {
        val phone = Phone()
        phone.sendSome(8)
        val reply = phone.request("Kommer", a, b, c)
        assertEquals(Limits.RATE_LIMITED, code(reply))
        assertEquals(
            "Not drafted: that's 10 texts in the last hour; try again after 09.30. 2 of the 3 would go now.",
            say(reply),
        )
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.cancel("t9")))
    }

    @Test
    fun `a group is checked again at confirm, and refused whole`() {
        val phone = Phone()
        val drafted = phone.request("Kommer", a, b, c)
        phone.sendSome(8)
        val reply = phone.outbox.confirm(token(drafted), 1)
        assertEquals(Limits.RATE_LIMITED, code(reply))
        assertTrue(say(reply).startsWith("Not sent: that's 10 texts in the last hour"), say(reply))
        assertTrue(say(reply).endsWith(" 2 of the 3 would go now."), say(reply))
        assertTrue(phone.timers.isEmpty())
    }

    @Test
    fun `the same number given twice is one send against the limit per number`() {
        val phone = Phone()
        repeat(2) {
            val drafted = phone.request("Hei", a)
            phone.outbox.confirm(token(drafted), version(drafted))
            phone.fire()
        }
        assertEquals("Draft to +4700000001; +4700000002: 'Hei'", say(phone.request("Hei", a, b, a)))
    }

    @Test
    fun `a list that would pass the lookups an hour is refused before any lookup, saying how many are left`() {
        val phone = Phone()
        repeat(Recipients.LOOKUPS - 2) { phone.recipients.resolve("Per") }
        val before = phone.lookups
        val refused = phone.request("Hei", "Per", a, "Ola", "Kari")
        assertEquals(
            "Not drafted: these names need 3 lookups; lookups left this hour: 2. Say the numbers instead.",
            say(refused),
        )
        assertEquals(Limits.RATE_LIMITED, code(refused))
        assertEquals(before, phone.lookups)
    }

    @Test
    fun `a list whose names fit the lookups left is looked up`() {
        val phone = Phone()
        repeat(Recipients.LOOKUPS - 2) { phone.recipients.resolve("Per") }
        val picked = picks(phone.request("Hei", "Kari"))
        assertEquals(
            "Draft to Per Hansen, +4700000090; Kari Nordmann, +4700000012: 'Hei'",
            say(phone.request("Hei", "Per", picked[0])),
        )
    }

    @Test
    fun `two names with exactly two lookups left are both looked up`() {
        val phone = Phone()
        repeat(Recipients.LOOKUPS - 2) { phone.recipients.resolve("Per") }
        val asked = phone.request("Hei", "Per", "Kari")
        assertEquals(Recipients.LOOKUPS, phone.lookups)
        assertTrue(picks(asked).isNotEmpty())
    }

    @Test
    fun `a list with one name and no lookups left keeps the single name's refusal`() {
        val phone = Phone()
        repeat(Recipients.LOOKUPS) { phone.recipients.resolve("Per") }
        assertEquals(
            "Not drafted: too many name lookups; say the number instead.",
            say(phone.request("Hei", a, "Per")),
        )
    }

    @Test
    fun `a two-letter name counts as a lookup the list needs`() {
        val phone = Phone()
        repeat(Recipients.LOOKUPS - 1) { phone.recipients.resolve("Per") }
        assertEquals(
            "Not drafted: these names need 2 lookups; lookups left this hour: 1. Say the numbers instead.",
            say(phone.request("Hei", "Per", "Bo")),
        )
    }

    @Test
    fun `a recipient Android refused at once still counts against the limits`() {
        val phone = Phone()
        phone.refusing += b
        repeat(3) {
            val drafted = phone.request("Hei", a, b)
            phone.outbox.confirm(token(drafted), version(drafted))
            phone.fire()
        }
        assertTrue(phone.sent.none { it.first == b })
        assertTrue(phone.request("Hei", b) is Reply.Refused)
    }

    @Test
    fun `two names needing a choice ask about the first, then the second, keeping the first pick`() {
        val phone = Phone()
        val first = phone.request("Hei", "Kari", "Ola")
        val kariPick = picks(first)[0]
        assertTrue(say(first).startsWith("Which one? 1: Kari Nordmann"), say(first))
        val second = phone.request("Hei", kariPick, "Ola")
        assertTrue(say(second).startsWith("Which one? 1: Ola Nordmann"), say(second))
        val olaPick = picks(second)[0]
        val drafted = phone.request("Hei", kariPick, olaPick)
        assertEquals("Draft to Kari Nordmann, +4700000012; Ola Nordmann, +4700000056: 'Hei'", say(drafted))
    }

    @Test
    fun `a pick is used once, and a later request without it closes it`() {
        val phone = Phone()
        val kariPick = picks(phone.request("Hei", "Kari"))[0]
        phone.request("Hei", a)
        assertEquals(Recipients.UNKNOWN_PICK, code(phone.request("Hei", kariPick, b)))
    }

    @Test
    fun `through the tool, to may be a list of one to five strings`() {
        val phone = Phone()
        fun call(arguments: String) = SmsSend.call(
            phone.outbox,
            SmsSend.REQUEST,
            Json.parseToJsonElement(arguments).jsonObject,
            permitted = true,
            recipients = phone.recipients,
        )
        assertEquals(
            "Draft to +4700000001; +4700000002: 'Hei'",
            say(call("""{"to":["+4700000001","+4700000002"],"text":"Hei"}""")),
        )
        assertEquals("Draft to +4700000003: 'Hei'", say(call("""{"to":["+4700000003"],"text":"Hei"}""")))
        val five = (1..5).joinToString(",") { "\"+470000000$it\"" }
        assertTrue(call("""{"to":[$five],"text":"Hei"}""") is Reply.Answer)
        val six = (1..6).joinToString(",") { "\"+470000000$it\"" }
        for (wrong in listOf("""[]""", """[$six]""", """["+4700000001",4700000002]""", """[["+4700000001"]]""")) {
            val reply = call("""{"to":$wrong,"text":"Hei"}""")
            assertEquals("sms_send_request was given an argument of the wrong kind.", say(reply), wrong)
        }
    }

    @Test
    fun `the tool's schema says to is a string or a list of one to five strings`() {
        val to = SmsSend.TOOLS.first { it.name == SmsSend.REQUEST }.input.getValue("properties").jsonObject.getValue("to")
        assertEquals(
            """{"oneOf":[{"type":"string","minLength":1},""" +
                """{"type":"array","items":{"type":"string","minLength":1},"minItems":1,"maxItems":5}]}""",
            to.toString(),
        )
    }

    @Test
    fun `the tool's words tell the hub a list is allowed and one yes covers it`() {
        val words = SmsSend.TOOLS.first { it.name == SmsSend.REQUEST }.description
        assertTrue("a list of up to five" in words, words)
    }

}
