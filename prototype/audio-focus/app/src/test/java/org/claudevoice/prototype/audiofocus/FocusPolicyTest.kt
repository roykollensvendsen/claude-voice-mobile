package org.claudevoice.prototype.audiofocus

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyTest {
    private val losses = listOf(
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
    )

    @Test
    fun music_never_pauses_for_another_app_in_either_mode() {
        for (mode in Mode.entries) {
            for (change in losses + AudioManager.AUDIOFOCUS_GAIN) {
                assertFalse("$mode $change", FocusPolicy.react(mode, change).pause)
            }
        }
    }

    @Test
    fun the_player_lowers_its_own_volume_on_every_loss_when_it_ducks_itself() {
        for (change in losses) {
            assertEquals(FocusPolicy.DUCKED_VOLUME, FocusPolicy.react(Mode.SELF_DUCK, change).volume, 0f)
        }
    }

    @Test
    fun the_player_keeps_full_volume_when_android_does_the_ducking() {
        for (change in losses) {
            assertEquals(1f, FocusPolicy.react(Mode.SYSTEM_DUCK, change).volume, 0f)
        }
    }

    @Test
    fun full_volume_comes_back_when_focus_comes_back() {
        for (mode in Mode.entries) {
            assertEquals(1f, FocusPolicy.react(mode, AudioManager.AUDIOFOCUS_GAIN).volume, 0f)
        }
    }

    @Test
    fun only_the_self_ducking_player_asks_android_not_to_duck_it() {
        assertTrue(FocusPolicy.willPauseWhenDucked(Mode.SELF_DUCK))
        assertFalse(FocusPolicy.willPauseWhenDucked(Mode.SYSTEM_DUCK))
    }

    @Test
    fun a_request_to_pause_is_recorded_as_such_even_though_the_player_plays_on() {
        for (mode in Mode.entries) {
            val note = FocusPolicy.react(mode, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT).note
            assertTrue(note, note.contains("asked to pause"))
        }
    }

    @Test
    fun every_focus_change_has_a_readable_name() {
        assertEquals("LOSS_TRANSIENT_CAN_DUCK", FocusPolicy.name(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals("GAIN", FocusPolicy.name(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals("UNKNOWN(42)", FocusPolicy.name(42))
    }
}
