package org.claudevoice.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoticeTapTest {
    @Test
    fun `a granted request ends the line, whatever came before`() {
        assertEquals(NoticeTap.After.Granted, NoticeTap.after(granted = true, shown = true, refusalsInRow = 0))
        assertEquals(NoticeTap.After.Granted, NoticeTap.after(granted = true, shown = false, refusalsInRow = 1))
    }

    @Test
    fun `a refusal with no pause means Android did not ask, so the settings open`() {
        assertEquals(NoticeTap.After.OpenSettings, NoticeTap.after(granted = false, shown = false, refusalsInRow = 0))
    }

    @Test
    fun `a first request closed after it showed asks again on the next tap, not the settings`() {
        assertEquals(NoticeTap.After.AskAgain, NoticeTap.after(granted = false, shown = true, refusalsInRow = 0))
    }

    @Test
    fun `a second refusal in a row opens the settings, even when the request showed`() {
        assertEquals(NoticeTap.After.OpenSettings, NoticeTap.after(granted = false, shown = true, refusalsInRow = 1))
        assertEquals(NoticeTap.After.OpenSettings, NoticeTap.after(granted = false, shown = false, refusalsInRow = 1))
        assertEquals(NoticeTap.After.OpenSettings, NoticeTap.after(granted = false, shown = true, refusalsInRow = 5))
    }

    @Test
    fun `refusals in a row count up on asking again and start over once the settings open`() {
        assertEquals(1, NoticeTap.refusalsInRow(NoticeTap.After.AskAgain, refusalsInRow = 0))
        assertEquals(0, NoticeTap.refusalsInRow(NoticeTap.After.OpenSettings, refusalsInRow = 1))
        assertEquals(0, NoticeTap.refusalsInRow(NoticeTap.After.Granted, refusalsInRow = 1))
    }

    @Test
    fun `the line shows only while the listener runs with its notice hidden`() {
        assertEquals(true, NoticeTap.lineShows(listening = true, noticesAllowed = false))
        assertEquals(false, NoticeTap.lineShows(listening = true, noticesAllowed = true))
        assertEquals(false, NoticeTap.lineShows(listening = false, noticesAllowed = false))
    }
}
