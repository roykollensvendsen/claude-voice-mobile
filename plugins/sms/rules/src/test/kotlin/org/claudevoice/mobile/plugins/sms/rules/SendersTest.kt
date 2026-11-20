package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The sender as the hub hears it (ADR-CVM-010). A letter-named sender is chosen by whoever sends,
 * so it must not be able to flood or steer what the hub reads out. Every sender here is made up.
 */
class SendersTest {
    @Test
    fun `an ordinary sender is kept as it came`() {
        assertEquals("+4700000021", Senders.shown("+4700000021"))
        assertEquals("2244", Senders.shown("2244"))
        assertEquals("Eksempelbank", Senders.shown("Eksempelbank"))
    }

    @Test
    fun `control, format and line-separating characters are dropped`() {
        assertEquals("Eksempelbank", Senders.shown("Eksempel\u0000bank"))
        assertEquals("Eksempelbank", Senders.shown("Eksempel‮bank"))
        assertEquals("Eksempelbank", Senders.shown("Eksempel​bank"))
        assertEquals("Eksempel bank", Senders.shown("Eksempel\nbank"))
        assertEquals("Eksempel bank", Senders.shown("Eksempel bank"))
        assertEquals("Eksempel bank", Senders.shown("Eksempel bank"))
    }

    @Test
    fun `runs of spaces become one, and the ends are trimmed`() {
        assertEquals("Eksempel bank", Senders.shown("  Eksempel \t  bank  "))
    }

    @Test
    fun `a sender is cut to its first 40 characters, counted as Unicode counts them`() {
        assertEquals("a".repeat(Senders.LONGEST), Senders.shown("a".repeat(Senders.LONGEST + 1)))
        assertEquals("a".repeat(Senders.LONGEST), Senders.shown("a".repeat(Senders.LONGEST)))
        val faces = "😀".repeat(Senders.LONGEST + 1)
        assertEquals("😀".repeat(Senders.LONGEST), Senders.shown(faces))
    }

    @Test
    fun `a cut never leaves a space at the end`() {
        val spaced = "a".repeat(Senders.LONGEST - 1) + " b"
        assertEquals("a".repeat(Senders.LONGEST - 1), Senders.shown(spaced))
    }

    @Test
    fun `a sender with nothing left is an unknown sender`() {
        assertEquals(Senders.UNKNOWN, Senders.shown(""))
        assertEquals(Senders.UNKNOWN, Senders.shown(" ​\n"))
    }

    private val norway: (String) -> String? = { national -> if (national.length == 8) "+47$national" else null }

    @Test
    fun `a plus and digits is a number, up to six digits a short code, and anything else a name`() {
        assertEquals(Senders.Heard("+4700000021", Senders.Kind.NUMBER), Senders.heard("+4700000021", norway))
        assertEquals(Senders.Heard("224466", Senders.Kind.SHORT_CODE), Senders.heard("224466", norway))
        assertEquals(Senders.Heard("2", Senders.Kind.SHORT_CODE), Senders.heard("2", norway))
        assertEquals(Senders.Heard("Eksempelbank", Senders.Kind.NAME), Senders.heard("Eksempelbank", norway))
        assertEquals(Senders.Kind.NAME, Senders.heard("+", norway).kind)
        assertEquals(Senders.Kind.NAME, Senders.heard("+47 00", norway).kind)
        assertEquals(Senders.Kind.NAME, Senders.heard("4+7", norway).kind)
        assertEquals(Senders.Heard(Senders.UNKNOWN, Senders.Kind.NAME), Senders.heard("", norway))
    }

    @Test
    fun `seven digits or more with no plus is a national number, put in E164 form with the phone's country`() {
        assertEquals(Senders.Heard("+4700000021", Senders.Kind.NUMBER), Senders.heard("00000021", norway))
    }

    @Test
    fun `a national number that cannot be put in E164 form keeps its digits, still a number`() {
        assertEquals(Senders.Heard("2244668", Senders.Kind.NUMBER), Senders.heard("2244668", norway))
    }

    @Test
    fun `a short code or a name is never sent to the country lookup`() {
        val asked = mutableListOf<String>()
        val lookup: (String) -> String? = { asked += it; null }
        Senders.heard("224466", lookup)
        Senders.heard("Eksempelbank", lookup)
        Senders.heard("+4700000021", lookup)
        assertEquals(listOf<String>(), asked)
    }

    @Test
    fun `each kind has the name the answer uses`() {
        assertEquals(listOf("number", "short_code", "name"), Senders.Kind.entries.map { it.wire })
    }
}
