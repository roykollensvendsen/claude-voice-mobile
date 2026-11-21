package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipientsTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private val kari = Contact("Kari Nordmann", "+4700000012", "mobile")
    private val kariHome = Contact("Kari Nordmann", "+4700000034", "home")
    private val ola = Contact("Ola Nordmann", "+4700000056", "mobile")
    private val per = Contact("Per Hansen", "+4700000078", "work")

    /** A phone with a contact book, a clock the test moves, and a count of every lookup. */
    private inner class Phone {
        var now: Instant = Instant.parse("2026-11-21T07:30:00Z")
        var book = listOf(kari, kariHome, ola, per)
        var permitted = true
        var lookups = 0
        var asked = -1
        private var next = 0
        val clock = object : Clock() {
            override fun instant() = now

            override fun getZone(): ZoneId = ZoneId.of("Europe/Oslo")

            override fun withZone(zone: ZoneId) = this
        }
        val recipients = Recipients(clock, tokens = { "t${++next}" }) { matches, most ->
            asked = most
            if (permitted) {
                lookups += 1
                book.filter { matches(it.name) }.take(most)
            } else {
                null
            }
        }
    }

    private fun refused(resolved: Recipients.Resolved): Reply.Refused =
        (resolved as Recipients.Resolved.Answered).reply as Reply.Refused

    private fun choices(resolved: Recipients.Resolved): JsonObject =
        ((resolved as Recipients.Resolved.Answered).reply as Reply.Answer).content

    private fun picks(content: JsonObject) =
        content.getValue("choices").jsonArray.map { it.jsonObject.getValue("pick").jsonPrimitive.content }

    @Test
    fun `on the phone, picks are never the same twice`() {
        val recipients = Recipients.onThePhone { _, _ -> listOf(kari, kariHome) }
        val first = picks(choices(recipients.resolve("Kari")))
        val second = picks(choices(recipients.resolve("Kari")))
        assertEquals(4, (first + second).toSet().size)
        assertTrue(first.all { it.startsWith("pick-") })
    }

    @Test
    fun `a number given as a number is no lookup`() {
        val phone = Phone()
        assertEquals(Recipients.Resolved.To("+4700000012", null), phone.recipients.resolve("+4700000012"))
        assertEquals(Recipients.Resolved.To("12 34", null), phone.recipients.resolve("12 34"))
        assertEquals(0, phone.lookups)
    }

    @Test
    fun `a name with one mobile number is that number, with the name for the read-back`() {
        val phone = Phone()
        assertEquals(Recipients.Resolved.To("+4700000056", "Ola Nordmann"), phone.recipients.resolve("ola"))
        assertEquals(1, phone.lookups)
        assertEquals(Names.MOST, phone.asked)
    }

    @Test
    fun `a name with several numbers answers choices, each a pick and words, never a whole number`() {
        val phone = Phone()
        val content = choices(phone.recipients.resolve("Kari"))
        val items = content.getValue("choices").jsonArray.map { it.jsonObject }
        assertEquals(listOf("pick-t1", "pick-t2"), picks(content))
        assertEquals(
            listOf("Kari Nordmann, mobile ending 12", "Kari Nordmann, home ending 34"),
            items.map { it.getValue("say").jsonPrimitive.content },
        )
        assertEquals(
            "Which one? 1: Kari Nordmann, mobile ending 12; 2: Kari Nordmann, home ending 34.",
            content.getValue("say").jsonPrimitive.content,
        )
        for (contact in listOf(kari, kariHome)) {
            assertFalse(content.toString().contains(contact.number), contact.number)
        }
    }

    @Test
    fun `a contact with only a work number is a choice of one, never a silent draft`() {
        val phone = Phone()
        assertEquals(listOf("pick-t1"), picks(choices(phone.recipients.resolve("Hansen"))))
        assertEquals(Recipients.Resolved.To("+4700000078", "Per Hansen"), phone.recipients.resolve("pick-t1"))
    }

    @Test
    fun `a pick from the latest choices is that number, once`() {
        val phone = Phone()
        val picked = picks(choices(phone.recipients.resolve("Kari")))[1]
        assertEquals(Recipients.Resolved.To("+4700000034", "Kari Nordmann"), phone.recipients.resolve(picked))
        val again = refused(phone.recipients.resolve(picked))
        assertEquals(Recipients.UNKNOWN_PICK, again.code)
        assertEquals(1, phone.lookups)
    }

    @Test
    fun `a pick from another request is refused`() {
        val phone = Phone()
        val first = picks(choices(phone.recipients.resolve("Kari")))
        phone.book = phone.book + kari.copy(name = "Kari Hansen", number = "+4700000090")
        picks(choices(phone.recipients.resolve("Hansen")))
        val stale = refused(phone.recipients.resolve(first[0]))
        assertEquals(Recipients.UNKNOWN_PICK, stale.code)
        assertEquals("That choice is from another request; say the name again.", stale.say)
        assertEquals(Recipients.UNKNOWN_PICK, refused(phone.recipients.resolve("pick-made-up")).code)
    }

    @Test
    fun `a draft from a name or a number closes the open choices`() {
        val phone = Phone()
        val open = picks(choices(phone.recipients.resolve("Kari")))
        phone.recipients.resolve("Ola")
        assertEquals(Recipients.UNKNOWN_PICK, refused(phone.recipients.resolve(open[0])).code)
        val again = picks(choices(phone.recipients.resolve("Kari")))
        phone.recipients.resolve("+4700000001")
        assertEquals(Recipients.UNKNOWN_PICK, refused(phone.recipients.resolve(again[0])).code)
    }

    @Test
    fun `a query under two letters is refused, and is no lookup`() {
        val phone = Phone()
        for (query in listOf("K", "K.", " K 7 ")) {
            val reply = refused(phone.recipients.resolve(query))
            assertEquals(Outbox.INVALID_RECIPIENT, reply.code, query)
            assertEquals("Say at least two letters of a name, or the number.", reply.say)
        }
        assertEquals(0, phone.lookups)
        assertEquals(Names.NOT_FOUND, refused(phone.recipients.resolve("Al")).code)
        assertEquals(1, phone.lookups)
    }

    @Test
    fun `no match is not found, and says so`() {
        val reply = refused(Phone().recipients.resolve("Nils"))
        assertEquals(Names.NOT_FOUND, reply.code)
        assertEquals("No contact is called Nils; say another name or the number.", reply.say)
    }

    @Test
    fun `ten lookups in an hour block the eleventh until the oldest is an hour old`() {
        val phone = Phone()
        repeat(10) {
            phone.recipients.resolve("Nils")
            phone.now = phone.now.plus(Duration.ofMinutes(1))
        }
        val blocked = refused(phone.recipients.resolve("Ola"))
        assertEquals(Limits.RATE_LIMITED, blocked.code)
        assertEquals("Not drafted: too many name lookups; say the number instead.", blocked.say)
        assertEquals(10, phone.lookups)
        assertEquals(Recipients.Resolved.To("+4700000001", null), phone.recipients.resolve("+4700000001"))
        phone.now = Instant.parse("2026-11-21T08:30:00Z")
        assertEquals(Recipients.Resolved.To("+4700000056", "Ola Nordmann"), phone.recipients.resolve("Ola"))
    }

    @Test
    fun `nine lookups in the last hour leave room for a tenth`() {
        val phone = Phone()
        repeat(9) { phone.recipients.resolve("Nils") }
        assertEquals(Recipients.Resolved.To("+4700000056", "Ola Nordmann"), phone.recipients.resolve("Ola"))
    }

    @Test
    fun `a lookup the phone may not make is refused with where to allow it, and is not counted`() {
        val phone = Phone()
        phone.permitted = false
        repeat(11) {
            val reply = refused(phone.recipients.resolve("Ola"))
            assertEquals(SmsSend.PERMISSION_MISSING, reply.code)
            assertEquals(Recipients.NO_CONTACTS, reply.say)
        }
        phone.permitted = true
        assertEquals(Recipients.Resolved.To("+4700000056", "Ola Nordmann"), phone.recipients.resolve("Ola"))
        assertTrue(Recipients.NO_CONTACTS.contains("Contacts"))
    }
}
