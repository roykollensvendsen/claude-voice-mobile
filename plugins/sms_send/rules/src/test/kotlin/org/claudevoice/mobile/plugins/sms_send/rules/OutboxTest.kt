package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OutboxTest {
    // Made-up numbers, in the +47 00 range no one is given.
    private val to = "+4700000001"
    private val other = "+4700000002"

    /** A clock at 08.30 Oslo time, and a timer that runs only when the test says so. */
    private inner class Phone(delay: Duration = Outbox.DEFAULT_DELAY) {
        var now: Instant = Instant.parse("2026-11-21T07:30:00Z")
        val timers = mutableListOf<Pair<Duration, Runnable>>()
        val sent = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        var failing = false
        private var next = 0
        val outbox = Outbox(
            clock = object : Clock() {
                override fun instant() = now

                override fun getZone(): ZoneId = ZoneId.of("Europe/Oslo")

                override fun withZone(zone: ZoneId) = this
            },
            later = { wait, task -> timers += wait to task },
            send = { (number, text) ->
                if (failing) throw IllegalStateException("radio off")
                sent += number to text
            },
            failed = { failures += it },
            tokens = { "t${++next}" },
            delay = delay,
        )

        fun fire() = timers.removeAt(0).second.run()
    }

    private fun content(reply: Reply): JsonObject = (reply as Reply.Answer).content

    private fun token(reply: Reply) = content(reply)["token"]!!.jsonPrimitive.content

    private fun version(reply: Reply) = content(reply)["version"]!!.jsonPrimitive.int

    private fun say(reply: Reply) = when (reply) {
        is Reply.Answer -> reply.content["say"]!!.jsonPrimitive.content
        is Reply.Refused -> reply.say
    }

    private fun code(reply: Reply) = (reply as Reply.Refused).code

    @Test
    fun `a request keeps a draft and sends nothing, and reads it back`() {
        val phone = Phone()
        val drafted = phone.outbox.request(to, "Kommer om ti")
        assertEquals("Draft to +4700000001: 'Kommer om ti'", say(drafted))
        assertEquals(1, version(drafted))
        assertTrue(phone.sent.isEmpty() && phone.timers.isEmpty())
    }

    @Test
    fun `confirm sends the stored text of the latest version, after the undo delay`() {
        val phone = Phone()
        val first = phone.outbox.request(to, "Kommer om ti")
        val second = phone.outbox.request(to, "Kommer om tjue")
        assertEquals(token(first), token(second))
        assertEquals(2, version(second))
        val confirmed = phone.outbox.confirm(token(second), 2)
        assertEquals(
            "Sending to +4700000001 in 10 seconds. Say stop to cancel. I can't yet confirm delivery.",
            say(confirmed),
        )
        assertTrue(phone.sent.isEmpty())
        assertEquals(Duration.ofSeconds(10), phone.timers.single().first)
        phone.fire()
        assertEquals(listOf(to to "Kommer om tjue"), phone.sent)
    }

    @Test
    fun `confirm takes no text of its own, so only the stored text can go`() {
        val phone = Phone()
        val drafted = phone.outbox.request(to, "Kommer om ti")
        val arguments = JsonObject(
            mapOf(
                "token" to JsonPrimitive(token(drafted)),
                "version" to JsonPrimitive(1),
                "text" to JsonPrimitive("Noe annet"),
            ),
        )
        val reply = SmsSend.call(phone.outbox, SmsSend.CONFIRM, arguments, permitted = true)
        assertTrue(reply is Reply.Refused)
        assertTrue(phone.sent.isEmpty() && phone.timers.isEmpty())
    }

    @Test
    fun `a stale version is refused, and nothing is armed`() {
        val phone = Phone()
        val first = phone.outbox.request(to, "Kommer om ti")
        phone.outbox.request(to, "Kommer om tjue")
        val reply = phone.outbox.confirm(token(first), version(first))
        assertEquals(Outbox.STALE_VERSION, code(reply))
        assertEquals("It changed; read it back first.", say(reply))
        assertTrue(phone.timers.isEmpty() && phone.sent.isEmpty())
    }

    @Test
    fun `an undo delay under the floor is refused, and the floor itself is allowed`() {
        assertThrows<IllegalArgumentException> { Phone(delay = Duration.ofMillis(4_999)) }
        assertEquals(Duration.ofSeconds(5), Phone(delay = Duration.ofSeconds(5)).let {
            it.outbox.confirm(token(it.outbox.request(to, "Hei")), 1)
            it.timers.single().first
        })
    }

    @Test
    fun `stop before the timer means nothing goes`() {
        val phone = Phone()
        val drafted = phone.outbox.request(to, "Kommer om ti")
        phone.outbox.confirm(token(drafted), 1)
        assertEquals("Stopped, not sent.", say(phone.outbox.cancel(token(drafted))))
        phone.fire()
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun `stop after the timer says it is too late, without claiming delivery`() {
        val phone = Phone()
        val drafted = phone.outbox.request(to, "Kommer om ti")
        phone.outbox.confirm(token(drafted), 1)
        phone.fire()
        assertEquals(
            "Too late to stop; it was handed to the phone at 08.30. I can't yet confirm delivery.",
            say(phone.outbox.cancel(token(drafted))),
        )
    }

    @Test
    fun `when stop and the timer race, exactly one wins, every time`() {
        repeat(RACES) {
            val sent = AtomicInteger()
            var timer: Runnable? = null
            val outbox = Outbox(
                clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
                later = { _, task -> timer = task },
                send = { _ -> sent.incrementAndGet() },
                failed = {},
                tokens = { "t" },
            )
            outbox.confirm(token(outbox.request(to, "Hei")), 1)
            val start = CyclicBarrier(2)
            val done = CountDownLatch(1)
            thread { start.await(); timer!!.run(); done.countDown() }
            start.await()
            val stopped = say(outbox.cancel("t")) == "Stopped, not sent."
            done.await()
            assertEquals(if (stopped) 0 else 1, sent.get())
        }
    }

    @Test
    fun `a draft once sent, stopped or never made cannot be confirmed`() {
        val phone = Phone()
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.confirm("t9", 1)))
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.cancel("t9")))
        val drafted = phone.outbox.request(to, "Hei")
        phone.outbox.cancel(token(drafted))
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.confirm(token(drafted), 1)))
        val again = phone.outbox.request(other, "Hei")
        phone.outbox.confirm(token(again), 1)
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.confirm(token(again), 1)))
        assertEquals(1, phone.timers.size)
    }

    @Test
    fun `a request after one was confirmed makes a new draft, not a new version of it`() {
        val phone = Phone()
        val first = phone.outbox.request(to, "Hei")
        phone.outbox.confirm(token(first), 1)
        val second = phone.outbox.request(to, "Hei igjen")
        assertTrue(token(first) != token(second))
        assertEquals(1, version(second))
    }

    @Test
    fun `a number not in E164 form is refused`() {
        val phone = Phone()
        for (bad in listOf("4700000001", "+0700000001", "+47 00000001", "+47", "+4700000001234567", "")) {
            assertEquals(Outbox.INVALID_RECIPIENT, code(phone.outbox.request(bad, "Hei")), bad)
        }
        val empty = phone.outbox.request(to, "")
        assertTrue(empty is Reply.Refused && empty.code == null && phone.timers.isEmpty())
    }

    @Test
    fun `a send the radio refuses is logged on the phone, never thrown`() {
        val phone = Phone()
        phone.failing = true
        phone.outbox.confirm(token(phone.outbox.request(to, "Hei")), 1)
        phone.fire()
        assertEquals(listOf(Outbox.SEND_FAILED), phone.failures)
    }

    @Test
    fun `without the permission to send, every tool is refused with the way to grant it`() {
        val phone = Phone()
        for (tool in SmsSend.TOOLS.map { it.name }) {
            val reply = SmsSend.call(phone.outbox, tool, JsonObject(emptyMap()), permitted = false)
            assertEquals(SmsSend.PERMISSION_MISSING, code(reply))
            assertTrue(say(reply).contains("Settings, Apps, claude-voice, Permissions, SMS"))
        }
    }

    @Test
    fun `the tools are offered by exactly the names the hub's deny rule expects`() {
        assertEquals(
            listOf("sms_send_request", "sms_send_confirm", "sms_send_cancel"),
            SmsSend.TOOLS.map { it.name },
        )
        assertEquals(listOf("sms_send_confirm"), SmsSend.CONFIRMS)
    }

    @Test
    fun `the phone's outbox gives each draft a token no one can guess, and arms a real timer stop still beats`() {
        val sent = mutableListOf<String>()
        val outbox = Outbox.onThePhone(send = { (number) -> sent += number }, failed = {})
        val first = outbox.request(to, "Hei")
        val second = outbox.request(other, "Hei")
        assertTrue(token(first) != token(second))
        assertTrue(Regex("[0-9a-f-]{36}").matches(token(first)), token(first))
        assertTrue(say(outbox.confirm(token(first), 1)).startsWith("Sending to $to in 10 seconds."))
        assertEquals("Stopped, not sent.", say(outbox.cancel(token(first))))
        assertTrue(sent.isEmpty())
    }

    /** A made-up number for the [n]th recipient. */
    private fun number(n: Int) = "+47000003%02d".format(n)

    /** Drafts and confirms a text to each of [count] numbers, from the [from]th on. */
    private fun Phone.confirmAll(count: Int, from: Int = 0) = (from until from + count).map {
        val drafted = outbox.request(number(it), "Hei")
        outbox.confirm(token(drafted), version(drafted))
        token(drafted)
    }

    @Test
    fun `a text with a link, a long number or over 1000 characters is refused at request, and nothing is kept`() {
        val phone = Phone()
        for (text in listOf("Se https://example.no", "Ring 123 456 789", "a".repeat(1001))) {
            val reply = phone.outbox.request(to, text)
            assertEquals(Limits.TEXT_REFUSED, code(reply), text)
            assertTrue(say(reply).startsWith("Not drafted: "), say(reply))
        }
        assertEquals(Outbox.NO_DRAFT, code(phone.outbox.confirm("t1", 1)))
    }

    @Test
    fun `the eleventh send in an hour is refused at request, saying when to try again`() {
        val phone = Phone()
        phone.confirmAll(10)
        val reply = phone.outbox.request(number(10), "Hei")
        assertEquals(Limits.RATE_LIMITED, code(reply))
        assertEquals("Not drafted: that's 10 texts in the last hour; try again after 09.30.", say(reply))
    }

    @Test
    fun `a draft made under the limit is refused at confirm once the limit is reached`() {
        val phone = Phone()
        val waiting = phone.outbox.request(number(10), "Hei")
        phone.confirmAll(10)
        val reply = phone.outbox.confirm(token(waiting), 1)
        assertEquals(Limits.RATE_LIMITED, code(reply))
        assertEquals("Not sent: that's 10 texts in the last hour; try again after 09.30.", say(reply))
        assertEquals(10, phone.timers.size)
        phone.now = phone.now.plus(Duration.ofHours(1))
        assertTrue(say(phone.outbox.confirm(token(waiting), 1)).startsWith("Sending to"))
    }

    @Test
    fun `held drafts never count, a send handed to the phone still counts, and a stopped one stops counting`() {
        val phone = Phone()
        repeat(12) { phone.outbox.request(number(20 + it), "Hei") }
        phone.confirmAll(10)
        repeat(10) { phone.fire() }
        assertEquals(Limits.RATE_LIMITED, code(phone.outbox.request(number(10), "Hei")))
        val other = Phone()
        val stopped = other.confirmAll(10)
        other.outbox.cancel(stopped.first())
        assertEquals(1, version(other.outbox.request(number(10), "Hei")))
    }

    @Test
    fun `three sends to one number in ten minutes stop a fourth to it, and ten minutes later it may go`() {
        val phone = Phone()
        repeat(3) {
            val drafted = phone.outbox.request(to, "Hei $it")
            phone.outbox.confirm(token(drafted), version(drafted))
        }
        val reply = phone.outbox.request(to, "Hei igjen")
        assertEquals("Not drafted: that's 3 texts to that number in 10 minutes; try again after 08.40.", say(reply))
        assertEquals(1, version(phone.outbox.request(other, "Hei")))
        phone.now = phone.now.plus(Duration.ofMinutes(10))
        assertEquals(1, version(phone.outbox.request(to, "Hei igjen")))
    }

    private companion object {
        const val RACES = 200
    }
}
