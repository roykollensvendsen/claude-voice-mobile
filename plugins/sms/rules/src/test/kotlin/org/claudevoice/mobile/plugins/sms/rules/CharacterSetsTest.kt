package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Android's regex engine (ICU) refuses the `(?U)` flag that the JVM takes, so the sets are
// written out. These tests hold them to what the flag meant on the JVM, character by character.
class CharacterSetsTest {
    private val SURROGATES = 0xD800..0xDFFF

    private fun same(written: String, meant: String) {
        val ours = Regex(written)
        val jvm = Regex(meant)
        val differ = (0..Character.MAX_CODE_POINT)
            .filter { it !in SURROGATES }
            .map { String(Character.toChars(it)) }
            .filter { ours.matches(it) != jvm.matches(it) }
        assertEquals(listOf<String>(), differ.map { "U+%04X".format(it.codePointAt(0)) })
    }

    @Test
    fun `white space is every space and line break Unicode names, as the JVM's (?U) took it`() {
        same(CharacterSets.WHITE_SPACE, "(?U)\\s")
    }

    @Test
    fun `hidden is every control and format character that is not white space`() {
        same(CharacterSets.HIDDEN, "(?U)[\\p{Cc}\\p{Cf}&&\\S]")
    }

    @Test
    fun `neither set leans on a flag Android refuses`() {
        for (set in listOf(CharacterSets.WHITE_SPACE, CharacterSets.HIDDEN)) {
            assertTrue(set.startsWith("[") && "(?" !in set && "&&" !in set, set)
        }
    }
}
