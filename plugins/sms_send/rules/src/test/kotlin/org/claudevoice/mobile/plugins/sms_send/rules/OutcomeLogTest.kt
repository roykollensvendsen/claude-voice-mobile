package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime

/**
 * How each held text ended, as `sms_send_outcomes` gives it (ADR-CVM-010, "Delivered"): one item
 * per recipient, oldest first, with the same cursor, acknowledgement, resend, drops and reset as
 * `sms_since`. The tokens here are made up.
 */
class OutcomeLogTest {
    private class Counted : Chance {
        private var cursors = 0

        override fun installPart() = "p4w8c1z6"

        override fun cursor(): String {
            cursors += 1
            return "Qm7Tz2pL" + cursors.toString().padStart(4, '0')
        }
    }

    private val at = OffsetDateTime.parse("2026-11-22T14:02:21+01:00")
    private val now = at.toInstant()

    private fun log() = OutcomeLog.fresh(Counted())

    private fun OutcomeLog.ended(token: String, recipient: Int, reason: Reason, time: OffsetDateTime = at) =
        record(SendOutcome(token, recipient, reason, time), now)

    private fun JsonObject.items() = getValue("outcomes").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `each outcome is given with its id, token, position, word, time and phrase, as the schema says`() {
        val log = log()
        log.ended("t-9f3a1c", 1, Reason.HANDED)
        log.ended("t-9f3a1c", 2, Reason.NO_SERVICE)
        log.ended("t-9f3a1c", 1, Reason.REPORTED_DELIVERED, at.plusSeconds(9))
        val answer = log.since(null, now)
        assertEquals(listOf<String>(), SchemaCheck.errors(answer, SchemaCheck.schema("sms-send-outcomes-answer.json")))
        val items = answer.items()
        assertEquals(listOf("p4w8c1z6-1", "p4w8c1z6-2", "p4w8c1z6-3"), items.map { it.string("id") })
        assertEquals(listOf("sent", "failed", "delivered"), items.map { it.string("outcome") })
        assertEquals(listOf(1, 2, 1), items.map { it.getValue("recipient").jsonPrimitive.int })
        assertEquals("no service", items[1].string("reason"))
        assertEquals("t-9f3a1c", items[1].string("token"))
        assertEquals("2026-11-22T14:02:30+01:00", items[2].string("at"))
    }

    @Test
    fun `an empty answer still fits the schema and has a cursor`() {
        val answer = log().since(null, now)
        assertEquals(listOf<String>(), SchemaCheck.errors(answer, SchemaCheck.schema("sms-send-outcomes-answer.json")))
        assertTrue(answer.items().isEmpty())
        assertEquals("Qm7Tz2pL0001", answer.string("cursor"))
    }

    @Test
    fun `an outcome goes only once an answer that gave it is acknowledged`() {
        val log = log()
        log.ended("t-1", 1, Reason.HANDED)
        val first = log.since(null, now)
        assertEquals(1, log.since(null, now).items().size, "not yet acknowledged, so given again")
        log.ended("t-2", 1, Reason.BY_OWNER)
        val next = log.since(first.string("cursor"), now)
        assertEquals(listOf("p4w8c1z6-2"), next.items().map { it.string("id") })
        assertEquals(1, log.size)
    }

    @Test
    fun `past 500 kept, or after 7 days, an outcome goes and is counted as dropped`() {
        val log = log()
        log.ended("t-old", 1, Reason.HANDED, at.minusDays(8))
        for (i in 1..OutcomeLog.MOST_KEPT + 2) log.ended("t-$i", 1, Reason.HANDED)
        val answer = log.since(null, now)
        assertEquals(3, answer.getValue("dropped").jsonPrimitive.int)
        assertEquals(OutcomeLog.MOST_KEPT, log.size)
        assertEquals(OutcomeLog.LONGEST_KEPT, Duration.ofDays(7))
    }

    @Test
    fun `a cursor this phone never gave says reset and removes nothing`() {
        val log = log()
        log.ended("t-1", 1, Reason.HANDED)
        val answer = log.since("Zz9fremmed00", now)
        assertEquals(true, answer.getValue("reset").jsonPrimitive.content.toBoolean())
        assertEquals(1, answer.items().size)
        assertEquals(1, log.size)
    }

    @Test
    fun `a token over 64 characters, or a position outside 1 to 5, is never kept`() {
        val log = log()
        assertThrows<IllegalArgumentException> { log.ended("t".repeat(65), 1, Reason.HANDED) }
        assertThrows<IllegalArgumentException> { log.ended("", 1, Reason.HANDED) }
        assertThrows<IllegalArgumentException> { log.ended("t-1", 0, Reason.HANDED) }
        assertThrows<IllegalArgumentException> { log.ended("t-1", 6, Reason.HANDED) }
        log.ended("t".repeat(64), 5, Reason.HANDED)
        log.ended("t", 1, Reason.HANDED)
        assertEquals(2, log.size)
    }

    @Test
    fun `a log read back from its bytes goes on where it was`() {
        val log = log()
        log.ended("t-1", 1, Reason.HANDED)
        log.ended("t-1", 2, Reason.RADIO_OFF, OffsetDateTime.parse("2026-11-22T09:00:00.25+05:45"))
        val given = log.since(null, now)
        val back = OutcomeLog.fromBytes(log.toBytes(), Counted())
        assertEquals(given.items(), back.since(null, now).items())
        back.ended("t-2", 1, Reason.MAYBE_SENT)
        assertEquals(listOf("p4w8c1z6-3"), back.since(given.string("cursor"), now).items().map { it.string("id") })
    }

    @Test
    fun `bytes of another format, a reason the log does not know, or bytes cut short are not a log`() {
        val log = log()
        log.ended("t-1", 1, Reason.HANDED)
        val good = log.toBytes()
        val format = good.copyOf().also { it[3] = 9 }
        assertThrows<IOException> { OutcomeLog.fromBytes(format, Counted()) }
        assertThrows<IOException> { OutcomeLog.fromBytes(good.copyOf(good.size - 3), Counted()) }
        val unknown = String(good, Charsets.ISO_8859_1).replace("HANDED", "HANDEX").toByteArray(Charsets.ISO_8859_1)
        assertThrows<IOException> { OutcomeLog.fromBytes(unknown, Counted()) }
    }

    @Test
    fun `a change is reported once, so the plugin knows when to save`() {
        val log = log()
        log.ended("t-1", 1, Reason.HANDED)
        assertTrue(log.takeChanged())
        assertEquals(false, log.takeChanged())
        log.markChanged()
        assertTrue(log.takeChanged())
    }
}
