package org.claudevoice.prototype.audiofocus

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * What kind of sound the player says it plays. During a Bluetooth call Android caps media
 * at about the call's own volume and sends it down the headset's call link, so the book
 * can end up far too quiet whatever the player's own volume is. Playing as another kind of
 * sound puts it on another volume stream, which may escape that cap.
 */
enum class SoundType(val label: String, val usage: Int, val contentType: Int, val stream: Int) {
    MEDIA("media", AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_MUSIC),
    CALL("call", AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.STREAM_VOICE_CALL),
    ACCESSIBILITY(
        "accessibility",
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioManager.STREAM_ACCESSIBILITY,
    ),
    ASSISTANT("assistant", AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.STREAM_MUSIC),
    ;

    companion object {
        fun fromSaved(name: String?): SoundType = entries.firstOrNull { it.name == name } ?: MEDIA
    }
}
