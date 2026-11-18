package org.claudevoice.prototype.micmute

import java.time.Instant
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of the mic-mute test app that decide a pass or a fail, measured off the phone. */
class MeasureTest {
    @Test
    fun a_line_carries_the_wall_clock_the_time_since_start_and_the_measurement() {
        val line = LogLine.format(
            Instant.parse("2026-11-18T10:15:30.123Z"), 12_345, "M1b", "reading",
            linkedMapOf("captures" to "1", "silenced" to "true"),
        )
        assertEquals("2026-11-18T10:15:30.123Z +12.345s M1b reading captures=1 silenced=true", line)
    }

    @Test
    fun a_line_stays_one_line_whatever_a_field_holds() {
        val line = LogLine.format(Instant.EPOCH, 0, "M6", "note", mapOf("text" to "a\nb c"))
        assertFalse(line.contains('\n'))
        assertEquals("1970-01-01T00:00:00Z +0.000s M6 note text=\"a b c\"", line)
    }

    @Test
    fun a_tone_is_not_silence_however_often_it_crosses_zero() {
        val tone = ShortArray(960) { if (it % 20 == 0) 0 else 1000 }
        assertEquals(912, Silence.nonZero(tone))
        assertNull(Silence.firstSilentRun(tone, minRun = 48))
    }

    @Test
    fun silence_starts_at_the_first_sample_of_a_long_enough_run_of_zeros() {
        val samples = ShortArray(200) { if (it < 100) 500 else 0 }
        samples[50] = 0
        assertEquals(100, Silence.firstSilentRun(samples, minRun = 48))
    }

    @Test
    fun a_run_of_zeros_cut_short_by_the_buffer_end_is_not_yet_silence() {
        val samples = ShortArray(120) { if (it < 100) 500 else 0 }
        assertNull(Silence.firstSilentRun(samples, minRun = 48))
    }

    @Test
    fun a_second_tap_within_700_ms_is_ignored_and_one_after_it_counts() {
        val debounce = Debounce(windowMs = 700)
        assertTrue(debounce.accept(1_000))
        assertFalse(debounce.accept(1_699))
        assertTrue(debounce.accept(1_700))
    }

    @Test
    fun the_95th_percentile_is_by_nearest_rank() {
        val twenty = (1..20).map { it * 10L }
        assertEquals(190L, Percentile.of(twenty, 95))
        assertEquals(null, Percentile.of(listOf(), 95))
        assertEquals(42L, Percentile.of(listOf(42L), 95))
    }

    @Test
    fun an_unmute_seen_while_muted_is_a_leak_even_when_put_right_at_once() {
        val watch = MuteWatch()
        watch.expect(muted = true)
        assertFalse(watch.observe(muted = true))
        assertTrue(watch.observe(muted = false))
        assertFalse(watch.observe(muted = true))
        assertEquals(1, watch.leaks)
    }

    @Test
    fun an_unmute_the_test_app_asked_for_is_no_leak() {
        val watch = MuteWatch()
        watch.expect(muted = true)
        watch.expect(muted = false)
        assertFalse(watch.observe(muted = false))
        assertEquals(0, watch.leaks)
    }

    @Test
    fun a_schedule_mutes_some_words_and_not_others_and_never_all_the_same() {
        repeat(50) { seed ->
            val schedule = Schedule.random(slots = 12, random = Random(seed))
            val muted = schedule.muted.count { it }
            assertTrue("seed $seed: $muted of 12 muted", muted in 4..8)
        }
    }

    @Test
    fun a_schedule_mutes_through_the_whole_of_a_muted_slot_and_ends_unmuted() {
        val schedule = Schedule(listOf(false, true, false), slotMs = 10_000)
        assertEquals(false, schedule.mutedAt(9_999))
        assertEquals(true, schedule.mutedAt(10_000))
        assertEquals(true, schedule.mutedAt(19_999))
        assertEquals(false, schedule.mutedAt(20_000))
        assertEquals(false, schedule.mutedAt(31_000))
        assertEquals("-M-", schedule.pattern)
    }

    @Test
    fun a_schedule_starts_at_the_next_whole_minute_so_the_laptop_can_start_with_it() {
        assertEquals(1_763_460_060_000, Schedule.nextMinute(1_763_460_000_001))
        assertEquals(1_763_460_060_000, Schedule.nextMinute(1_763_460_000_000))
    }

    @Test
    fun one_silenced_capture_is_android_saying_the_claude_app_hears_nothing() {
        assertEquals("pass", Reading.judge(listOf(true)))
    }

    @Test
    fun a_reading_with_no_capture_or_several_fails_rather_than_being_guessed_at() {
        assertEquals("fail: 0 captures", Reading.judge(listOf()))
        assertEquals("fail: 2 captures", Reading.judge(listOf(true, true)))
    }

    @Test
    fun a_capture_that_is_not_silenced_while_muted_fails() {
        assertEquals("fail: not silenced", Reading.judge(listOf(false)))
    }

    @Test
    fun the_mute_tone_falls_and_the_unmute_tone_rises() {
        val falling = Tones.falling(sampleRate = 48_000)
        val rising = Tones.rising(sampleRate = 48_000)
        assertTrue(falling.isNotEmpty() && rising.isNotEmpty())
        assertTrue(Tones.zeroCrossings(falling, 0, falling.size / 3) > Tones.zeroCrossings(falling, falling.size * 2 / 3, falling.size))
        assertTrue(Tones.zeroCrossings(rising, 0, rising.size / 3) < Tones.zeroCrossings(rising, rising.size * 2 / 3, rising.size))
    }

    @Test
    fun silence_that_begins_in_one_buffer_and_lasts_into_the_next_is_found_where_it_began() {
        val clock = SilenceClock(minRun = 48)
        assertNull(clock.feed(ShortArray(100) { if (it < 80) 500 else 0 }))
        assertEquals(80L, clock.feed(ShortArray(100)))
        assertNull(clock.feed(ShortArray(100)))
    }

    @Test
    fun after_a_reset_the_clock_looks_for_the_next_silence() {
        val clock = SilenceClock(minRun = 48)
        assertEquals(0L, clock.feed(ShortArray(100)))
        clock.reset()
        assertNull(clock.feed(ShortArray(10) { 500 }))
        assertEquals(10L, clock.feed(ShortArray(100)))
    }
}
