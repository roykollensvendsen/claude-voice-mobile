package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The parts one broadcast brings. A long message comes as several parts from one sender, in
 * order, and is kept as one. Every sender and text here is made up.
 */
class ArrivalsTest {
    private fun part(from: String?, body: String?) = Arrivals.Part(from, body)

    @Test
    fun `one part is one message`() {
        assertEquals(listOf(Arrivals.Message("+4700000021", "Ses vi?")), Arrivals.joined(listOf(part("+4700000021", "Ses vi?"))))
    }

    @Test
    fun `parts from one sender in a row are joined in order, without anything between them`() {
        val parts = listOf(part("+4700000021", "Ses vi klokka "), part("+4700000021", "seks, "), part("+4700000021", "ved kaia?"))
        assertEquals(listOf(Arrivals.Message("+4700000021", "Ses vi klokka seks, ved kaia?")), Arrivals.joined(parts))
    }

    @Test
    fun `a part from another sender starts another message`() {
        val parts = listOf(part("+4700000021", "en"), part("Eksempel", "to"), part("Eksempel", " og tre"), part("+4700000021", "fire"))
        val expected = listOf(
            Arrivals.Message("+4700000021", "en"),
            Arrivals.Message("Eksempel", "to og tre"),
            Arrivals.Message("+4700000021", "fire"),
        )
        assertEquals(expected, Arrivals.joined(parts))
    }

    @Test
    fun `a part with no sender or no text counts as empty, never as missing`() {
        assertEquals(listOf(Arrivals.Message("", "")), Arrivals.joined(listOf(part(null, null))))
        assertEquals(listOf(Arrivals.Message("", "en to")), Arrivals.joined(listOf(part(null, "en "), part("", "to"))))
    }

    @Test
    fun `no parts are no messages`() {
        assertEquals(listOf<Arrivals.Message>(), Arrivals.joined(listOf()))
    }
}
