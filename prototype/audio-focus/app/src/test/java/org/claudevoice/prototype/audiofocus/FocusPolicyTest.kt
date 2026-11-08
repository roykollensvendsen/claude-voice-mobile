package org.claudevoice.prototype.audiofocus

import android.media.AudioAttributes
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

class WatchPolicyTest {
    private val media = AudioAttributes.USAGE_MEDIA

    @Test
    fun the_watching_player_never_asks_for_focus() {
        assertFalse(FocusPolicy.requestsFocus(Mode.WATCH))
        assertTrue(FocusPolicy.requestsFocus(Mode.SELF_DUCK))
        assertTrue(FocusPolicy.requestsFocus(Mode.SYSTEM_DUCK))
    }

    @Test
    fun the_watching_player_ducks_while_an_assistant_or_a_call_is_playing() {
        for (other in listOf(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_VOICE_COMMUNICATION)) {
            assertEquals(
                FocusPolicy.DUCKED_VOLUME,
                FocusPolicy.watchVolume(setOf(media, other), AudioManager.MODE_NORMAL, othersRecording = false),
                0f,
            )
        }
    }

    @Test
    fun the_watching_player_ducks_while_the_phone_is_in_a_call_mode() {
        for (mode in listOf(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_IN_CALL)) {
            assertEquals(FocusPolicy.DUCKED_VOLUME, FocusPolicy.watchVolume(setOf(media), mode, othersRecording = false), 0f)
        }
    }

    @Test
    fun the_watching_player_plays_at_full_volume_when_only_media_plays() {
        assertEquals(1f, FocusPolicy.watchVolume(setOf(media), AudioManager.MODE_NORMAL, othersRecording = false), 0f)
        assertEquals(1f, FocusPolicy.watchVolume(emptySet(), AudioManager.MODE_NORMAL, othersRecording = false), 0f)
    }

    // Round 2 on the phone: Claude's voice mode played as plain media in a normal audio mode,
    // so nothing above fired. It does always listen, and this app never records.
    @Test
    fun the_watching_player_ducks_while_another_app_listens_to_the_microphone() {
        assertEquals(
            FocusPolicy.DUCKED_VOLUME,
            FocusPolicy.watchVolume(setOf(media), AudioManager.MODE_NORMAL, othersRecording = true),
            0f,
        )
    }
}
