package org.claudevoice.mobile.capabilities.contacts

import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private val kari = Entry("Kari Nordmann", "+4700000012", "mobile")
    private val kariHome = Entry("Kari Nordmann", "+4700000034", "home")
    private val ola = Entry("Ola Nordmann", "+4700000056", "mobile")
    private var reads = 0

    private fun contacts(permitted: Boolean, vararg entries: Entry) = Contacts({ permitted }) {
        reads += 1
        entries.toList()
    }

    @Test
    fun `answers the numbers whose contact's name the caller's rule accepts, in the book's order`() {
        val found = contacts(true, kari, ola, kariHome).numbers { it.startsWith("Kari") }
        assertEquals(listOf(kari, kariHome), found)
    }

    @Test
    fun `answers every match, however many, so the caller's rule decides what is shown`() {
        val many = (1..12).map { kari.copy(number = "+47000000${10 + it}") }
        assertEquals(many, contacts(true, *many.toTypedArray()).numbers { true })
    }

    @Test
    fun `answers nothing, and reads nothing, when the phone may not read contacts`() {
        assertNull(contacts(false, kari).numbers { true })
        assertEquals(0, reads)
    }

    @Test
    fun `a number's label is mobile, home, work or other`() {
        assertEquals("mobile", Contacts.label(Phone.TYPE_MOBILE))
        assertEquals("home", Contacts.label(Phone.TYPE_HOME))
        assertEquals("work", Contacts.label(Phone.TYPE_WORK))
        assertEquals("other", Contacts.label(Phone.TYPE_WORK_MOBILE))
        assertEquals("other", Contacts.label(Phone.TYPE_OTHER))
    }

    // A made-up stand-in for Android's formatter, as it answers with the SIM in Norway.
    private val norway = { raw: String -> if (raw.startsWith("+")) raw else "+47$raw" }
    private val nothing = { _: String -> null }

    @Test
    fun `the number the contacts provider wrote is used as it is, and is no guess`() {
        val entry = Contacts.entry("Kari Nordmann", "+4700000012", "000 00 012", Phone.TYPE_MOBILE) { error("not asked") }
        assertEquals(Entry("Kari Nordmann", "+4700000012", "mobile"), entry)
    }

    @Test
    fun `a number saved in full international form is used as written, and is no guess`() {
        val entry = Contacts.entry("Kari Nordmann", null, "+47 000-00 012", Phone.TYPE_MOBILE) { error("not asked") }
        assertEquals(Entry("Kari Nordmann", "+4700000012", "mobile"), entry)
    }

    @Test
    fun `a local number in country NO becomes +47, marked as a guess`() {
        val entry = Contacts.entry("Ola Hansen", null, "000 00 034", Phone.TYPE_MOBILE, norway)
        assertEquals(Entry("Ola Hansen", "+4700000034", "mobile", guessed = "+47"), entry)
    }

    @Test
    fun `a local number with a trunk zero names the code before it`() {
        val uk = { raw: String -> "+44" + raw.removePrefix("0") }
        val entry = Contacts.entry("Sam", null, "07000 000001", Phone.TYPE_MOBILE, uk)
        assertEquals(Entry("Sam", "+447000000001", "mobile", guessed = "+44"), entry)
    }

    @Test
    fun `a local number the formatter cannot place stays as written, and the rules leave it out`() {
        val entry = Contacts.entry("Ola Hansen", null, "000 00 034", Phone.TYPE_MOBILE, nothing)
        assertEquals(Entry("Ola Hansen", "00000034", "mobile"), entry)
    }

    @Test
    fun `a row with no name or no number is no entry`() {
        assertNull(Contacts.entry(null, "+4700000012", null, Phone.TYPE_MOBILE, norway))
        assertNull(Contacts.entry("Kari", null, null, Phone.TYPE_MOBILE, norway))
        assertNull(Contacts.entry("Kari", null, " - ", Phone.TYPE_MOBILE, norway))
    }

    @Test
    fun `a guess whose code cannot be told from the number says only that it was guessed`() {
        assertEquals("+47", Contacts.code("+4700000034", "00000034"))
        assertEquals("+44", Contacts.code("+447000000001", "07000000001"))
        assertEquals(Contacts.SOME_CODE, Contacts.code("+4712345678", "00000034"))
    }
}
