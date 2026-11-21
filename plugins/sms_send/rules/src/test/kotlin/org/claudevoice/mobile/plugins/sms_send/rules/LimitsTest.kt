package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LimitsTest {
    private val now = Instant.parse("2026-11-21T07:30:00Z")
    private val to = "+4700000001"

    private fun minutesAgo(minutes: Long, number: String = to) =
        Counted(number, now.minus(Duration.ofMinutes(minutes)))

    /** [n] sends to as many numbers, the newest [minutes] ago and one minute apart. */
    private fun spread(n: Int, minutes: Long) =
        (0 until n).map { minutesAgo(minutes + it, "+47000001%02d".format(it)) }

    @Test
    fun `a text of exactly 1000 characters may go, and 1001 may not`() {
        assertNull(Limits.text("a".repeat(1000)))
        assertEquals(
            "Not drafted: a text can be at most 1000 characters, and this one has 1001.",
            Limits.text("a".repeat(1001)),
        )
    }

    @Test
    fun `characters are counted as a person counts them, so an emoji is one`() {
        assertNull(Limits.text("😀".repeat(1000)))
    }

    @Test
    fun `six digits in a row may go, and seven may not`() {
        assertNull(Limits.text("Koden er 123456"))
        assertEquals(DIGITS, Limits.text("Koden er 1234567"))
    }

    @Test
    fun `digits are counted across spaces, dots and dashes`() {
        assertNull(Limits.text("12-34-56 og 7"))
        for (run in listOf("123 456 789", "12.34.567", "123-4567", "+47 000 00 001", "1 2 3 4 5 6 7", "１２３４５６７")) {
            assertEquals(DIGITS, Limits.text("Ring $run nå"), run)
        }
    }

    @Test
    fun `digits apart from each other by words are separate runs`() {
        assertNull(Limits.text("Kl 10.30 eller 11, rom 1234"))
    }

    @Test
    fun `a link is refused, with a scheme, with www, or as a bare domain`() {
        val links = listOf(
            "Se https://example.no",
            "HTTP://EXAMPLE",
            "Les www.example",
            "Les WWW.EXAMPLE.NO",
            "Gå til example.no/side",
            "Se example.no",
            "Skriv til navn@example.no",
            "Se sub.example.com.",
        )
        for (text in links) assertEquals(LINKS, Limits.text(text), text)
    }

    @Test
    fun `ordinary words with a dot, numbers with a decimal point and abbreviations are no link`() {
        for (text in listOf("Hei.Kommer snart", "Det er 1.5 km", "Kl. 10 i morgen", "Osv. Ok", "e.g. dette")) {
            assertNull(Limits.text(text), text)
        }
    }

    @Test
    fun `nine sends in the last hour leave room for a tenth`() {
        assertNull(Limits.blocked(spread(9, 0), to, now))
    }

    @Test
    fun `ten sends in the last hour block the next until the oldest is an hour old`() {
        val counted = spread(10, 0)
        assertEquals(
            Blocked("that's 10 texts in the last hour", now.minus(Duration.ofMinutes(9)).plus(Duration.ofHours(1))),
            Limits.blocked(counted, "+4799999999", now),
        )
    }

    @Test
    fun `a send exactly an hour old no longer counts toward the hour`() {
        assertNull(Limits.blocked(spread(9, 0) + minutesAgo(60, "+4700000999"), "+4799999999", now))
    }

    @Test
    fun `thirty sends in a day block the next until the oldest is a day old`() {
        val counted = (0 until 30).map {
            Counted("+47000002%02d".format(it), now.minus(Duration.ofMinutes(90L + it * 40)))
        }
        assertEquals(
            Blocked("that's 30 texts in the last 24 hours", counted.last().at.plus(Duration.ofDays(1))),
            Limits.blocked(counted, "+4799999999", now),
        )
    }

    @Test
    fun `three sends to one number in ten minutes block a fourth to it, but not to another`() {
        val counted = listOf(minutesAgo(1), minutesAgo(5), minutesAgo(9))
        assertEquals(
            Blocked("that's 3 texts to that number in 10 minutes", now.plus(Duration.ofMinutes(1))),
            Limits.blocked(counted, to, now),
        )
        assertNull(Limits.blocked(counted, "+4700000002", now))
        assertNull(Limits.blocked(counted.drop(1), to, now))
    }

    @Test
    fun `when two limits block, the answer names the one that lifts last`() {
        val counted = spread(7, 0) + listOf(minutesAgo(1), minutesAgo(2), minutesAgo(3))
        val blocked = Limits.blocked(counted, to, now)!!
        assertEquals("that's 10 texts in the last hour", blocked.words)
        assertEquals(now.minus(Duration.ofMinutes(6)).plus(Duration.ofHours(1)), blocked.until)
    }

    @Test
    fun `when two limits lift at the same moment, the answer names the hour's`() {
        val counted = (49L..55L).map { minutesAgo(it, "+470000040$it") } + (3L..5L).map { minutesAgo(it) }
        assertEquals(
            Blocked("that's 10 texts in the last hour", now.plus(Duration.ofMinutes(5))),
            Limits.blocked(counted, to, now),
        )
    }

    @Test
    fun `the refusal says when to try again, in the phone's own time`() {
        val blocked = Blocked("that's 10 texts in the last hour", Instant.parse("2026-11-21T08:21:30Z"))
        assertEquals(
            "Not sent: that's 10 texts in the last hour; try again after 09.21.",
            blocked.say("Not sent", ZoneId.of("Europe/Oslo")),
        )
    }

    private companion object {
        const val DIGITS =
            "Not drafted: texts can't contain more than 6 digits in a row, such as a phone or account number."
        const val LINKS = "Not drafted: texts can't contain links or web addresses."
    }
}
