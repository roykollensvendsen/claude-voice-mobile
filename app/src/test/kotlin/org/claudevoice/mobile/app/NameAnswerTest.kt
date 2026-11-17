package org.claudevoice.mobile.app

import org.claudevoice.mobile.core.Confirmed
import org.junit.Assert.assertEquals
import org.junit.Test

class NameAnswerTest {
    @Test
    fun every_answer_has_its_own_words() {
        val words = Confirmed.entries.map(NameAnswer::of)
        assertEquals(Confirmed.entries.size, words.toSet().size)
        assertEquals(R.string.name_paired_as, NameAnswer.of(Confirmed.PairedAs))
    }
}
