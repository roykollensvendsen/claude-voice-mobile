package org.claudevoice.mobile.app

import org.claudevoice.mobile.core.Confirmed
import org.junit.Assert.assertEquals
import org.junit.Test

class NameAnswerTest {
    @Test
    fun every_answer_has_its_own_words() {
        val words = Confirmed.entries.map(NameAnswer::of)
        assertEquals(Confirmed.entries.size, words.toSet().size)
        assertEquals(R.string.name_already_named, NameAnswer.of(Confirmed.AlreadyNamed))
    }

    @Test
    fun the_step_is_shown_only_while_the_phone_has_no_name() {
        assertEquals(true, NameAnswer.shown(null))
        assertEquals(false, NameAnswer.shown("pixel-8"))
    }

    @Test
    fun only_a_kept_name_ends_the_step() {
        assertEquals(listOf(Confirmed.Kept), Confirmed.entries.filter(NameAnswer::ends))
    }
}
