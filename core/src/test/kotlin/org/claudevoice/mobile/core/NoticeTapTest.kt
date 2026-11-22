package org.claudevoice.mobile.core

import org.claudevoice.mobile.core.NoticeTap.After
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoticeTapTest {
    // A refusal from the line: whether this install asked before, Android's rationale flag read
    // just before the request and again after the refusal, and the refusals in a row so far.
    private fun refused(asked: Boolean, before: Boolean, after: Boolean, inRow: Int = 0) =
        NoticeTap.after(false, askedBefore = asked, flagBefore = before, flagAfter = after, refusalsInRow = inRow)

    @Test
    fun `a granted request ends the line, whatever came before`() {
        assertEquals(After.Granted, NoticeTap.after(true, askedBefore = true, flagBefore = false, flagAfter = false, 1))
        assertEquals(After.Granted, NoticeTap.after(true, askedBefore = false, flagBefore = true, flagAfter = true, 0))
    }

    @Test
    fun `a first refusal of a request Android showed asks again on the next tap`() {
        assertEquals(After.AskAgain, refused(asked = true, before = false, after = true))
    }

    @Test
    fun `a second refusal Android showed, with the flag true before, asks again once more`() {
        assertEquals(After.AskAgain, refused(asked = true, before = true, after = false))
    }

    @Test
    fun `a refusal with the flag false before and after, after an earlier ask, means Android did not ask`() {
        assertEquals(After.OpenSettings, refused(asked = true, before = false, after = false))
    }

    @Test
    fun `with no earlier ask, the flag false before and after is a request closed unanswered, so ask again`() {
        assertEquals(After.AskAgain, refused(asked = false, before = false, after = false))
    }

    @Test
    fun `a second refusal in a row opens the settings, even when Android showed the request`() {
        assertEquals(After.OpenSettings, refused(asked = true, before = false, after = true, inRow = 1))
        assertEquals(After.OpenSettings, refused(asked = true, before = true, after = false, inRow = 1))
        assertEquals(After.OpenSettings, refused(asked = false, before = false, after = false, inRow = 1))
    }

    @Test
    fun `refusals in a row count up on asking again and start over once the settings open`() {
        assertEquals(1, NoticeTap.refusalsInRow(After.AskAgain, refusalsInRow = 0))
        assertEquals(0, NoticeTap.refusalsInRow(After.OpenSettings, refusalsInRow = 1))
        assertEquals(0, NoticeTap.refusalsInRow(After.Granted, refusalsInRow = 1))
    }

    @Test
    fun `the line shows only while the listener runs with its notice hidden`() {
        assertEquals(true, NoticeTap.lineShows(listening = true, noticesAllowed = false))
        assertEquals(false, NoticeTap.lineShows(listening = true, noticesAllowed = true))
        assertEquals(false, NoticeTap.lineShows(listening = false, noticesAllowed = false))
    }
}
