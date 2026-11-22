package org.claudevoice.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeLineTest {
    private val did = mutableListOf<String>()

    // Android's rationale flag as the line reads it, and whether the ask record shows an earlier ask.
    private var flag = false
    private var asked = true

    private fun line() = NoticeLine(
        ask = { did.add("ask") },
        openSettings = { did.add("settings") },
        flag = { flag },
        askedBefore = { asked },
    )

    private val line = line()

    // One tap the owner refuses, with the flag [before] the request and [after] the refusal.
    private fun NoticeLine.refuse(before: Boolean, after: Boolean) {
        flag = before
        tap(canAsk = true)
        flag = after
        answered(granted = false)
    }

    @Test
    fun a_tap_always_asks_first() {
        line.tap(canAsk = true)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun a_first_refusal_android_showed_asks_again_on_the_next_tap() {
        line.refuse(before = false, after = true)
        assertEquals(listOf("ask"), did)
        line.tap(canAsk = true)
        assertEquals(listOf("ask", "ask"), did)
    }

    @Test
    fun a_second_refusal_android_showed_with_the_flag_true_before_asks_again_once_more() {
        line.refuse(before = true, after = false)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun an_automatic_refusal_after_an_earlier_ask_opens_the_settings() {
        line.refuse(before = false, after = false)
        assertEquals(listOf("ask", "settings"), did)
    }

    @Test
    fun a_request_closed_twice_opens_the_settings_on_the_second() {
        asked = false
        line.refuse(before = false, after = false)
        assertEquals(listOf("ask"), did)
        asked = true
        line.refuse(before = false, after = false)
        assertEquals(listOf("ask", "ask", "settings"), did)
    }

    @Test
    fun with_no_earlier_ask_the_flag_false_both_times_asks_again() {
        asked = false
        line.refuse(before = false, after = false)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun the_earlier_ask_is_read_before_the_tap_asks() {
        val reads = mutableListOf<String>()
        val watched = NoticeLine(
            ask = { reads.add("ask") },
            openSettings = {},
            flag = { reads.add("flag").let { false } },
            askedBefore = { reads.add("record").let { true } },
        )
        watched.tap(canAsk = true)
        assertEquals(listOf("record", "flag", "ask"), reads)
    }

    @Test
    fun a_second_refusal_in_a_row_opens_the_settings_even_when_android_showed_it() {
        line.refuse(before = false, after = true)
        line.refuse(before = true, after = true)
        assertEquals(listOf("ask", "ask", "settings"), did)
    }

    @Test
    fun an_answer_the_line_did_not_ask_for_changes_nothing() {
        line.answered(granted = false)
        assertEquals(listOf<String>(), did)
    }

    @Test
    fun a_grant_starts_the_count_over() {
        line.refuse(before = false, after = true)
        line.tap(canAsk = true)
        line.answered(granted = true)
        line.refuse(before = false, after = true)
        assertEquals(listOf("ask", "ask", "ask"), did)
    }

    @Test
    fun before_android_13_the_tap_opens_the_settings_and_never_reads_the_flag() {
        var read = false
        val old = NoticeLine(
            ask = { did.add("ask") },
            openSettings = { did.add("settings") },
            flag = { read = true; false },
            askedBefore = { read = true; true },
        )
        old.tap(canAsk = false)
        assertEquals(listOf("settings"), did)
        assertEquals(false, read)
    }

    // The screen turned or was rebuilt while a request from the line was open: a new line takes the
    // old one's state, so the answer still counts (voice-architect's f2 on #165).
    private fun rebuilt(from: NoticeLine) = line().apply { restore(from.saved()) }

    @Test
    fun the_reading_before_the_request_survives_a_rebuild() {
        flag = true
        line.tap(canAsk = true)
        flag = false
        rebuilt(line).answered(granted = false)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun the_earlier_ask_read_at_the_tap_survives_a_rebuild() {
        asked = false
        line.tap(canAsk = true)
        asked = true
        rebuilt(line).answered(granted = false)
        assertEquals(listOf("ask"), did)
    }

    @Test
    fun an_automatic_refusal_after_a_rebuild_still_opens_the_settings() {
        line.tap(canAsk = true)
        rebuilt(line).answered(granted = false)
        assertEquals(listOf("ask", "settings"), did)
    }

    @Test
    fun the_refusals_in_a_row_survive_a_rebuild() {
        line.refuse(before = false, after = true)
        rebuilt(line).refuse(before = true, after = true)
        assertEquals(listOf("ask", "ask", "settings"), did)
    }

    @Test
    fun nothing_saved_is_a_fresh_line() {
        val fresh = line()
        fresh.restore(null)
        fresh.answered(granted = false)
        assertEquals(emptyList<String>(), did)
    }

    @Test
    fun a_saved_state_of_the_wrong_size_is_ignored() {
        val fresh = line()
        fresh.restore(intArrayOf(1))
        fresh.answered(granted = false)
        assertEquals(emptyList<String>(), did)
    }

    @Test
    fun a_saved_state_from_the_version_before_is_a_fresh_line() {
        val fresh = line()
        fresh.restore(intArrayOf(1, 1, 0))
        fresh.answered(granted = false)
        assertEquals(emptyList<String>(), did)
    }
}
