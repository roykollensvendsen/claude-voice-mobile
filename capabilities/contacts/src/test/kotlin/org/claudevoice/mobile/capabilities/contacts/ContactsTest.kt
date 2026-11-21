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
        val found = contacts(true, kari, ola, kariHome).numbers({ it.startsWith("Kari") }, most = 5)
        assertEquals(listOf(kari, kariHome), found)
    }

    @Test
    fun `answers at most as many numbers as the caller asks for`() {
        val found = contacts(true, kari, kariHome, ola).numbers({ true }, most = 2)
        assertEquals(listOf(kari, kariHome), found)
    }

    @Test
    fun `answers nothing, and reads nothing, when the phone may not read contacts`() {
        assertNull(contacts(false, kari).numbers({ true }, most = 5))
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

    @Test
    fun `a number is the phone's own international form when it has one, else as written without spaces or dashes`() {
        assertEquals("+4700000012", Contacts.number("+4700000012", "000 00 012"))
        assertEquals("+4700000012", Contacts.number(null, "+47 000-00 012"))
        assertEquals("00000012", Contacts.number(null, "000 00 012"))
        assertNull(Contacts.number(null, null))
        assertNull(Contacts.number(null, " - "))
    }
}
