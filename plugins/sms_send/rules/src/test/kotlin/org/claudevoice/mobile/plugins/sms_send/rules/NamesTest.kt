package org.claudevoice.mobile.plugins.sms_send.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamesTest {
    // Made-up people, with numbers in the +47 00 range no one is given.
    private val kari = Contact("Kari Nordmann", "+4700000012", "mobile")
    private val kariHome = Contact("Kari Nordmann", "+4700000034", "home")
    private val ola = Contact("Ola Nordmann", "+4700000056", "mobile")

    @Test
    fun `a whole first name, a whole last name or the full name matches`() {
        for (query in listOf("Kari", "Nordmann", "Kari Nordmann")) {
            assertTrue(Names.matches(query, "Kari Nordmann"), query)
        }
    }

    @Test
    fun `case, accents and spaces around or between words do not matter`() {
        for (query in listOf("kari", "KARI", "  kari   nordmann ", "Kåri", "Kári")) {
            assertTrue(Names.matches(query, "Kari Nordmann"), query)
        }
        assertTrue(Names.matches("rene", "René Dupont"))
        assertTrue(Names.matches("Åse", "Ase Berg"))
    }

    @Test
    fun `a prefix, a part of a name, a middle name or a single letter never matches`() {
        for (query in listOf("Kar", "Nord", "ari", "K", "Kari Nord", "Nordmann Kari", "Marie")) {
            assertFalse(Names.matches(query, "Kari Marie Nordmann"), query)
        }
        assertFalse(Names.matches("Anne", "Anne-Lise Berg"))
        assertTrue(Names.matches("Anne-Lise", "Anne-Lise Berg"))
    }

    @Test
    fun `a name with one word matches itself, and an empty name matches nothing`() {
        assertTrue(Names.matches("Kari", "Kari"))
        assertFalse(Names.matches("Kari", ""))
        assertFalse(Names.matches("Kari", "   "))
    }

    @Test
    fun `letters are counted as a person counts them, with no digits, spaces or marks`() {
        assertEquals(0, Names.letters(""))
        assertEquals(1, Names.letters("K"))
        assertEquals(1, Names.letters(" K. 7 "))
        assertEquals(2, Names.letters("Øy"))
        assertEquals(1, Names.letters("Å"))
        assertEquals(1, Names.letters("Å"))
    }

    @Test
    fun `one mobile number for the name is the one recipient`() {
        assertEquals(Names.Decision.One(kari), Names.decide(listOf(kari)))
    }

    @Test
    fun `a contact with only a home or work number is a choice, never a silent draft`() {
        assertEquals(Names.Decision.Choose(listOf(kariHome)), Names.decide(listOf(kariHome)))
        val work = kari.copy(label = "work")
        assertEquals(Names.Decision.Choose(listOf(work)), Names.decide(listOf(work)))
    }

    @Test
    fun `more than one number is a choice of at most five`() {
        assertEquals(Names.Decision.Choose(listOf(kari, kariHome)), Names.decide(listOf(kari, kariHome)))
        val many = (1..7).map { kari.copy(number = "+470000002$it") }
        assertEquals(Names.Decision.Choose(many.take(5)), Names.decide(many))
        assertEquals(5, Names.MOST)
    }

    @Test
    fun `no match is not found`() {
        assertEquals(Names.Decision.None, Names.decide(emptyList()))
    }

    @Test
    fun `a number the phone cannot write in full international form is left out`() {
        val local = kari.copy(number = "900 00 012")
        assertEquals(Names.Decision.None, Names.decide(listOf(local)))
        assertEquals(Names.Decision.One(ola), Names.decide(listOf(local, ola)))
    }

    @Test
    fun `a choice names the contact and the label with the last two digits, never the whole number`() {
        assertEquals("Kari Nordmann, mobile ending 12", Names.choice(kari))
        assertEquals("Kari Nordmann, home ending 34", Names.choice(kariHome))
        assertFalse(Names.choice(kari).contains("+4700000012"))
    }
}
