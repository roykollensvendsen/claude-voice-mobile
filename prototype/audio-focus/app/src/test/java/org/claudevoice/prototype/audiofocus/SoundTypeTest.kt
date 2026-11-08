package org.claudevoice.prototype.audiofocus

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundTypeTest {
    @Test
    fun media_is_the_default_and_an_unknown_saved_name_falls_back_to_it() {
        assertEquals(SoundType.MEDIA, SoundType.fromSaved(null))
        assertEquals(SoundType.MEDIA, SoundType.fromSaved("NONSENSE"))
        assertEquals(SoundType.CALL, SoundType.fromSaved("CALL"))
    }

    @Test
    fun each_sound_type_plays_on_its_own_usage_and_volume_stream() {
        assertEquals(AudioAttributes.USAGE_MEDIA, SoundType.MEDIA.usage)
        assertEquals(AudioManager.STREAM_MUSIC, SoundType.MEDIA.stream)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, SoundType.CALL.usage)
        assertEquals(AudioManager.STREAM_VOICE_CALL, SoundType.CALL.stream)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, SoundType.ACCESSIBILITY.usage)
        assertEquals(AudioManager.STREAM_ACCESSIBILITY, SoundType.ACCESSIBILITY.stream)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, SoundType.ASSISTANT.usage)
        assertEquals(AudioManager.STREAM_MUSIC, SoundType.ASSISTANT.stream)
    }

    @Test
    fun the_watcher_does_not_duck_for_its_own_call_sound() {
        val call = AudioAttributes.USAGE_VOICE_COMMUNICATION
        assertEquals(
            1f,
            FocusPolicy.watchVolume(setOf(call), AudioManager.MODE_NORMAL, othersRecording = false, ownUsage = call),
            0f,
        )
    }

    @Test
    fun the_watcher_still_ducks_in_a_call_while_playing_as_a_call() {
        val call = AudioAttributes.USAGE_VOICE_COMMUNICATION
        assertEquals(
            0.4f,
            FocusPolicy.watchVolume(setOf(call), AudioManager.MODE_IN_COMMUNICATION, othersRecording = false, ownUsage = call),
            0f,
        )
        assertEquals(
            0.4f,
            FocusPolicy.watchVolume(setOf(call), AudioManager.MODE_NORMAL, othersRecording = true, ownUsage = call),
            0f,
        )
    }

    // Round 9: as media the book drowned under Claude even boosted; as a call it sat level with
    // Claude. Outside a call it should stay an ordinary podcast.
    @Test
    fun auto_plays_as_media_outside_a_call_and_as_a_call_inside_one() {
        assertEquals(SoundType.MEDIA, SoundType.AUTO.playsAs(AudioManager.MODE_NORMAL))
        assertEquals(SoundType.CALL, SoundType.AUTO.playsAs(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(SoundType.CALL, SoundType.AUTO.playsAs(AudioManager.MODE_IN_CALL))
    }

    @Test
    fun a_fixed_sound_type_plays_as_itself_in_every_mode() {
        for (type in listOf(SoundType.MEDIA, SoundType.CALL, SoundType.ACCESSIBILITY, SoundType.ASSISTANT)) {
            assertEquals(type, type.playsAs(AudioManager.MODE_NORMAL))
            assertEquals(type, type.playsAs(AudioManager.MODE_IN_COMMUNICATION))
        }
    }
}
