package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `any dotted word ending in letters is a link, in any case and whatever the ending`() {
        val links = listOf(
            "login-bank.ru", "pay.click", "x.shop", "evil.ru", "login.site", "EXAMPLE.COM", "Vg.No", "vg.no",
            "ca.ti", "navn@vert.ukjent", "se example.com/x", "WWW.x", "HTTPS://x", "Se 10.0.0.1/x",
        )
        for (text in links) {
            assertEquals(LINKS, Limits.text(text), text)
        }
    }

    @Test
    fun `a sentence run on without a space reads as a link, a false positive the owner can say again`() {
        assertEquals(LINKS, Limits.text("Ses i morgen.Takk"))
    }

    @Test
    fun `only a whole word that is a known abbreviation is no link, in any case and with a dot after`() {
        val words = listOf(
            "f.eks", "f.eks.", "F.eks.", "bl.a", "bl.a.", "kl.14", "m.m.", "dvs.", "E.G.", "Det er f.eks. bra",
            "Ta med noe («f.eks. kaffe») bl.a.,",
        )
        for (text in words) {
            assertNull(Limits.text(text), text)
        }
        for (text in listOf("xf.eks", "f.eks.no", "f.eks..")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
    }

    @Test
    fun `numbers with a decimal point, a slash between plain words and spaced abbreviations are no link`() {
        for (text in listOf("Det er 1.5 km", "Kl. 10 i morgen", "Osv. Ok", "e.g. dette", "og/eller 80 km/t")) {
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

    @Test
    fun `an address written as four numbers is a link, while a date is no link`() {
        for (text in listOf("5.9.1.2", "Se 10.0.0.1", "Gå til 192.0.2.10.", "(100.64.0.1)")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
        assertNotEquals(LINKS, Limits.text("Vi ses 21.11.2026"))
        assertNull(Limits.text("Versjon 1.2.3 er ute"))
    }

    @Test
    fun `look-alike dots and wide letters are read as the plain ones`() {
        for (text in listOf("evil\u3002ru", "evil\uFF0Eru", "evil\uFF61ru", "ｅｖｉｌ.ｒｕ", "www\u3002x")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
        assertNull(Limits.text("Takk\u3002 Ses"))
    }

    @Test
    fun `a punycode ending is an ending too, in any case`() {
        for (text in listOf("evil.xn--p1ai", "EVIL.XN--P1AI", "Se b.xn--90ais/x")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
    }

    @Test
    fun `a text with an invisible character is refused, so none can hide a link`() {
        for (text in listOf("evil\u200B.ru", "Hei\u200Bdu", "a\u00ADb", "\u2066Hei\u2069", "Hei\uFEFF")) {
            assertEquals(INVISIBLE, Limits.text(text), text)
        }
    }

    @Test
    fun `an emoji joined of several, or a flag of a part of a country, may go`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val toned = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"
        val rainbow = "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08"
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        for (emoji in listOf(family, toned, rainbow, england)) {
            assertNull(Limits.text("Hei $emoji ses"), emoji)
        }
    }

    @Test
    fun `a joiner or a tag anywhere else is still refused`() {
        val man = "\uD83D\uDC68"
        val tagG = "\uDB40\uDC67"
        val cancel = "\uDB40\uDC7F"
        for (text in listOf(
            "exa\u200Dmple.ru", "1\u200D2", "$man\u200Dx", "a\u200D$man", "Hei\u200D",
            "Hei$tagG", "Hei$tagG$cancel", "\uD83C\uDFF4$tagG", "$man$tagG$cancel",
        )) {
            assertEquals(INVISIBLE, Limits.text(text), text)
        }
    }

    @Test
    fun `a name with a symbol in it is a link too, while a symbol ending a sentence is none`() {
        for (text in listOf("i\u2764.ws", "x\u2603.com", "Se \u2603.net/x", "\u2764\uFE0F\u2764.ws", "x\u20DD.ru")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
        assertNull(Limits.text("Glad i deg \u2764. Ses"))
        assertNull(Limits.text("Det koster 5 \u20AC. Ok?"))
    }

    @Test
    fun `a price run into the next word reads as a link, a false positive kept on purpose`() {
        for (text in listOf("5\u20AC.Ok", "1.5$/t")) {
            assertEquals(LINKS, Limits.text(text), text)
        }
    }

    @Test
    fun `five numbers with dots are no address, while an address with a port is one`() {
        assertNull(Limits.text("1.2.3.4.5"))
        assertEquals(LINKS, Limits.text("10.0.0.1:8080"))
    }

    // ADR-CVM-010, "Dates and times in a text": each example the record names is a test of its own.
    @Test
    fun `a date with its year passes`() = assertNull(Limits.text("21.11.2026"))

    @Test
    fun `a date with a one-digit day passes`() = assertNull(Limits.text("1.12.2026"))

    @Test
    fun `a date written year first passes`() = assertNull(Limits.text("2026-11-21"))

    @Test
    fun `a range of two times with a dash passes`() = assertNull(Limits.text("14.30-16.00"))

    @Test
    fun `a range of two times with an en dash passes`() = assertNull(Limits.text("14.30\u201316.00"))

    @Test
    fun `a time after kl passes`() = assertNull(Limits.text("kl. 9:05"))

    @Test
    fun `a date and a time apart by a word pass`() = assertNull(Limits.text("21.11.2026 kl. 14.30"))

    // Four numbers with dots read as an address first, so the link rule refuses it before the digits.
    @Test
    fun `four numbers in pairs with dots are no time`() = assertEquals(LINKS, Limits.text("22.33.44.55"))

    @Test
    fun `four numbers in pairs with dashes are no date`() = assertEquals(DIGITS, Limits.text("22-33-44-55"))

    @Test
    fun `numbers of other lengths with dots are no date`() = assertEquals(DIGITS, Limits.text("1234.56.78901"))

    @Test
    fun `a date with more digits after it is no date`() = assertEquals(DIGITS, Limits.text("21.11.2026.55"))

    @Test
    fun `a date next to a time with no word between stays in`() =
        assertEquals(DIGITS, Limits.text("21.11.2026 14.30"))

    @Test
    fun `two dates side by side stay in`() = assertEquals(DIGITS, Limits.text("12.05.2034 13.08.1999"))

    @Test
    fun `four times side by side stay in`() = assertEquals(DIGITS, Limits.text("12.11 13.10 14.09 15.08"))

    @Test
    fun `a date between two numbers stays in`() = assertEquals(DIGITS, Limits.text("1234 21.11.2026 5678"))

    @Test
    fun `numbers apart by slashes are one run`() = assertEquals(DIGITS, Limits.text("41/23/45/67"))

    @Test
    fun `numbers apart by brackets and spaces are one run`() = assertEquals(DIGITS, Limits.text("(412) 34 567"))

    @Test
    fun `numbers apart by colons, commas, underscores, apostrophes or middle dots are one run`() {
        for (text in listOf("12:34:56:78", "12,34,56,78", "12_34_56_78", "12'34'56'78", "12\u00B734\u00B756\u00B778")) {
            assertEquals(DIGITS, Limits.text(text), text)
        }
    }

    @Test
    fun `a date at the end of a sentence passes, and one closing mark is allowed`() {
        for (text in listOf("Vi ses 21.11.2026.", "Vi ses 21.11.2026, ok?", "(kl. 14.30)", "Ses 21.11!")) {
            assertNull(Limits.text(text), text)
        }
    }

    @Test
    fun `only real days, months, hours and minutes are dates and times`() {
        for (text in listOf("32.11.2026", "21.13.2026", "21.11.2100", "24.00-25.00", "19.60-20.00")) {
            assertEquals(DIGITS, Limits.text("Se $text"), text)
        }
    }

    @Test
    fun `digits of another script are no date`() =
        assertEquals(DIGITS, Limits.text("\u0662\u0661.\u0661\u0661.\u0662\u0660\u0662\u0666"))

    private companion object {
        const val INVISIBLE = "Not drafted: the text has a hidden character. Leave out any emoji, or say it again."

        const val DIGITS =
            "Not drafted: texts can't contain more than 6 digits in a row, such as a phone or account number."
        const val LINKS = "Not drafted: texts can't contain links or web addresses."
    }
}
