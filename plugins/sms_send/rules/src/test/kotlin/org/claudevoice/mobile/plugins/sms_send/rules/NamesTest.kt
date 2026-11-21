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
    fun `more than one number is a choice of at most five, and says when more match`() {
        assertEquals(Names.Decision.Choose(listOf(kari, kariHome)), Names.decide(listOf(kari, kariHome)))
        val six = (1..6).map { kari.copy(number = "+470000002$it") }
        assertEquals(Names.Decision.Choose(six.take(5), more = true), Names.decide(six))
        assertEquals(Names.Decision.Choose(six.take(5), more = false), Names.decide(six.take(5)))
        assertEquals(5, Names.MOST)
    }

    @Test
    fun `rows with one number are one number, and a mobile label among them wins`() {
        val synced = listOf(kari.copy(label = "other"), kari, kari.copy(label = "home"))
        assertEquals(Names.Decision.One(kari), Names.decide(synced))
        assertEquals(Names.Decision.Choose(listOf(kariHome)), Names.decide(listOf(kariHome, kariHome)))
    }

    @Test
    fun `duplicates are dropped before the cap, so they never crowd out a real number`() {
        val found = List(5) { kari } + ola
        assertEquals(Names.Decision.Choose(listOf(kari, ola)), Names.decide(found))
    }

    @Test
    fun `numbers that cannot be sent to are left out before the cap`() {
        val local = (1..5).map { kari.copy(number = "900 00 01$it") }
        assertEquals(Names.Decision.One(ola), Names.decide(local + ola))
    }

    @Test
    fun `a shown name is cleaned and cut to 40 characters, as a sender's is`() {
        assertEquals("K".repeat(40), Names.shown("K".repeat(41)))
        assertEquals("Kari Nordmann", Names.shown("  Kari\u200B \n Nordmann\u202E "))
        assertEquals(40, Names.LONGEST)
        val long = kari.copy(name = "Kari " + "N".repeat(45))
        assertEquals("Kari " + "N".repeat(35) + ", mobile ending 12", Names.choice(long))
    }

    @Test
    fun `a guessed number alone is a choice, never a draft`() {
        val guessed = kari.copy(number = "+4700000034", guessed = "+47")
        assertEquals(Names.Decision.Choose(listOf(guessed)), Names.decide(listOf(guessed)))
    }

    @Test
    fun `a choice of a guessed number says which country code was guessed`() {
        val guessed = kari.copy(number = "+4700000034", guessed = "+47")
        assertEquals("Kari Nordmann, mobile ending 34, guessed +47", Names.choice(guessed))
    }

    @Test
    fun `of rows with one number, a mobile one wins, and then one the provider wrote over a guessed one`() {
        val guessed = kari.copy(guessed = "+47")
        assertEquals(Names.Decision.One(kari), Names.decide(listOf(guessed, kari)))
        assertEquals(Names.Decision.One(kari), Names.decide(listOf(kari, guessed)))
        val home = kari.copy(label = "home")
        assertEquals(Names.Decision.Choose(listOf(guessed)), Names.decide(listOf(home, guessed)))
        assertEquals(Names.Decision.Choose(listOf(home)), Names.decide(listOf(home.copy(guessed = "+47"), home)))
    }

    @Test
    fun `of rows with one number and nothing to tell them apart, the first found is kept`() {
        val other = kari.copy(name = "Kari Nordmann Berg")
        assertEquals(Names.Decision.One(kari), Names.decide(listOf(kari, other)))
        assertEquals(Names.Decision.One(other), Names.decide(listOf(other, kari)))
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
