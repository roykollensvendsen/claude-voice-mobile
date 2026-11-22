package org.claudevoice.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeLineTest {
    private val did = mutableListOf<String>()
    private val line = NoticeLine(ask = { did.add("ask") }, openSettings = { did.add("settings") })

    @Test
    fun a_tap_always_asks_first() {
        line.tap(canAsk = true)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun a_refusal_with_no_pause_opens_the_settings() {
        line.tap(canAsk = true)
        line.answered(granted = false)
        assertEquals(listOf("ask", "settings"), did)
    }

    @Test
    fun a_first_refusal_after_the_screen_paused_asks_again_on_the_next_tap() {
        line.tap(canAsk = true)
        line.leftScreen()
        line.answered(granted = false)
        assertEquals(listOf("ask"), did)
        line.tap(canAsk = true)
        assertEquals(listOf("ask", "ask"), did)
    }

    @Test
    fun a_second_refusal_in_a_row_opens_the_settings_even_when_the_screen_paused() {
        repeat(2) {
            line.tap(canAsk = true)
            line.leftScreen()
            line.answered(granted = false)
        }
        assertEquals(listOf("ask", "ask", "settings"), did)
    }

    @Test
    fun a_pause_before_the_tap_does_not_count_as_a_request_shown() {
        line.leftScreen()
        line.tap(canAsk = true)
        line.answered(granted = false)
        assertEquals(listOf("ask", "settings"), did)
    }

    @Test
    fun an_answer_the_line_did_not_ask_for_changes_nothing() {
        line.answered(granted = false)
        assertEquals(listOf<String>(), did)
    }

    @Test
    fun a_grant_starts_the_count_over() {
        line.tap(canAsk = true)
        line.leftScreen()
        line.answered(granted = false)
        line.tap(canAsk = true)
        line.leftScreen()
        line.answered(granted = true)
        line.tap(canAsk = true)
        line.leftScreen()
        line.answered(granted = false)
        assertEquals(listOf("ask", "ask", "ask"), did)
    }

    @Test
    fun where_android_has_no_request_to_show_the_tap_opens_the_settings() {
        line.tap(canAsk = false)
        assertEquals(listOf("settings"), did)
    }
}
