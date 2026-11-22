package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-back makes a stray number stand out (ADR-CVM-010, "Several recipients", as #189 and
 * #191 settled it): "not in your contacts" for a number given as digits that no contact has, and
 * "never texted before" for a number no sent text went to. Each needs its own reading. Both
 * marks for one number are one check, and that check is one of the lookups an hour.
 */
class StrayNumberTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private val a = "+4700000001"
    private val b = "+4700000002"
    private val per = Contact("Per Hansen", "+4700000090", "mobile")

    private val clock = Clock.fixed(Instant.parse("2026-11-21T07:30:00Z"), ZoneId.of("Europe/Oslo"))

    /** A phone whose contacts hold [contacts] and whose sent box went to [texted]; null means it may not read that. */
    private inner class Phone(val contacts: Set<String>?, val texted: Set<String>?) {
        var checks = 0
        val recipients = Recipients(
            clock,
            tokens = { "p1" },
            contact = { number -> contacts?.let { checks += 1; number in it } },
            texted = { number -> texted?.let { checks += 1; number in it } },
        ) { matches -> contacts?.let { listOf(per).filter { matches(it.name) } } }
        val outbox = Outbox(clock = clock, later = { _, _ -> }, send = { _, _ -> }, failed = {}, tokens = { "t1" })

        fun request(vararg to: String) = say(outbox.request(to.toList(), "Hei", recipients))
    }

    private fun say(reply: Reply) = when (reply) {
        is Reply.Answer -> reply.content.getValue("say").jsonPrimitive.content
        is Reply.Refused -> reply.say
    }

    @Test
    fun `a number no contact has and no text went to gets both marks`() {
        assertEquals(
            "Draft to +4700000001 (not in your contacts, never texted before): 'Hei'",
            Phone(contacts = emptySet(), texted = emptySet()).request(a),
        )
    }

    @Test
    fun `a number in the contacts that was texted before gets no mark, and is read back as the number`() {
        assertEquals("Draft to +4700000001: 'Hei'", Phone(contacts = setOf(a), texted = setOf(a)).request(a))
    }

    @Test
    fun `a number in the contacts that was never texted gets only that mark`() {
        assertEquals(
            "Draft to +4700000001 (never texted before): 'Hei'",
            Phone(contacts = setOf(a), texted = emptySet()).request(a),
        )
    }

    @Test
    fun `a number texted before but in no contact gets only that mark`() {
        assertEquals(
            "Draft to +4700000001 (not in your contacts): 'Hei'",
            Phone(contacts = emptySet(), texted = setOf(a)).request(a),
        )
    }

    @Test
    fun `without leave to read contacts or texts there is no mark and no claim`() {
        assertEquals("Draft to +4700000001: 'Hei'", Phone(contacts = null, texted = null).request(a))
    }

    @Test
    fun `a contact found by name is never marked as not in the contacts, and its mark costs no lookup`() {
        val phone = Phone(contacts = emptySet(), texted = emptySet())
        repeat(Recipients.LOOKUPS - 1) { phone.recipients.resolve("Per") }
        assertEquals("Draft to Per Hansen, +4700000090 (never texted before): 'Hei'", phone.request("Per"))
        assertEquals("Draft to +4700000001 (not checked): 'Hei'", phone.request(a))
    }

    @Test
    fun `each recipient in a list gets its own marks, in read-back order`() {
        assertEquals(
            "Draft to +4700000002; +4700000001 (not in your contacts, never texted before): 'Hei'",
            Phone(contacts = setOf(b), texted = setOf(b)).request(b, a),
        )
    }

    @Test
    fun `both marks for one number are one check, and one of the lookups an hour`() {
        val phone = Phone(contacts = setOf(per.number), texted = emptySet())
        repeat(Recipients.LOOKUPS - 1) { phone.recipients.resolve("Per") }
        phone.request(a)
        assertEquals(
            "Not drafted: too many name lookups; say the number instead.",
            say(phone.outbox.request("Per", "Hei", phone.recipients)),
        )
    }

    @Test
    fun `past the lookups an hour a number is read back as not checked, with neither mark`() {
        val phone = Phone(contacts = emptySet(), texted = emptySet())
        repeat(Recipients.LOOKUPS) { phone.recipients.resolve("Per") }
        val before = phone.checks
        assertEquals("Draft to +4700000001 (not checked): 'Hei'", phone.request(a))
        assertEquals(before, phone.checks)
    }

    @Test
    fun `a number checked when only the sent box may be read still counts as a lookup`() {
        val phone = Phone(contacts = null, texted = emptySet())
        repeat(Recipients.LOOKUPS) { phone.request("+47000001%02d".format(it)) }
        assertEquals("Draft to +4700000001 (not checked): 'Hei'", phone.request(a))
    }

    @Test
    fun `a number checked when only the contacts may be read still counts as a lookup`() {
        val phone = Phone(contacts = emptySet(), texted = null)
        repeat(Recipients.LOOKUPS) { phone.request("+47000001%02d".format(it)) }
        assertEquals("Draft to +4700000001 (not checked): 'Hei'", phone.request(a))
    }

    @Test
    fun `a number nothing may be read about costs no lookup`() {
        val phone = Phone(contacts = null, texted = null)
        repeat(Recipients.LOOKUPS) { phone.request("+47000001%02d".format(it)) }
        assertEquals("Draft to +4700000001: 'Hei'", phone.request(a))
    }

    @Test
    fun `a text refused before drafting costs no contacts check`() {
        val phone = Phone(contacts = emptySet(), texted = emptySet())
        phone.outbox.request(listOf(a), "", phone.recipients)
        assertEquals(0, phone.checks)
    }

    @Test
    fun `the tool's words tell the hub the never-texted mark needs the app to read texts`() {
        val words = SmsSend.TOOLS.first { it.name == SmsSend.REQUEST }.description
        assertTrue(
            "The never-texted mark needs the app to read texts; without that it is left out." in words,
            words,
        )
    }

    @Test
    fun `an outbox with no readings, as before, marks nothing`() {
        val box = Outbox(clock, { _, _ -> }, { _, _ -> }, {}, { "t1" })
        assertEquals("Draft to +4700000001: 'Hei'", say(box.request(a, "Hei")))
    }
}
