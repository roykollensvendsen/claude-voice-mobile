package org.claudevoice.prototype.audiofocus

import android.media.AudioAttributes
import android.media.AudioManager

/** Who lowers the music when another app takes the sound. */
enum class Mode(val label: String) {
    /** The player lowers its own volume, and asks Android not to duck it. */
    SELF_DUCK("self"),

    /** The player does nothing, and leaves any ducking to Android. */
    SYSTEM_DUCK("system"),

    /**
     * The player never asks for focus, so it can take nothing from another app. It ducks
     * itself while another app plays an assistant's voice or a call, or the phone is in a call.
     */
    WATCH("watch"),
    ;

    companion object {
        /** The mode that takes nothing from Claude: the others ended its call on the phone. */
        val DEFAULT = WATCH

        fun fromSaved(name: String?): Mode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How a level becomes sound: the player's own volume, which cannot go above 1, and a boost
 * in millibels on top of it, for levels above 100 %.
 */
data class Loudness(val volume: Float, val boostMillibels: Int)

/** What the player does with its volume after a focus change, and what it notes. */
data class Reaction(val volume: Float, val pause: Boolean, val note: String)

/**
 * The prototype's whole behaviour on a focus change. It never pauses: the question is
 * what Android asks for and what Roy hears, so a request to pause is logged, not obeyed.
 */
object FocusPolicy {
    /** How loud the player plays while ducked, unless the tester chooses otherwise. */
    const val DEFAULT_DUCK = 0.4f
    const val DUCKED_VOLUME = DEFAULT_DUCK

    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 400

    /** A slider's percent as a level, kept between 10 % and 400 %; above 100 % is a boost. */
    fun duckLevel(percent: Int): Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f

    /** A level split into the player's volume and a boost: 200 % is full volume plus 6 dB. */
    fun loudness(level: Float): Loudness =
        if (level <= 1f) {
            Loudness(level, 0)
        } else {
            Loudness(1f, Math.round(2000.0 * Math.log10(level.toDouble())).toInt())
        }

    fun willPauseWhenDucked(mode: Mode): Boolean = mode == Mode.SELF_DUCK

    fun requestsFocus(mode: Mode): Boolean = mode != Mode.WATCH

    private val talking = setOf(
        AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    )
    private val callModes = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)

    /**
     * The watching player's volume: ducked while another app records (this app never does, and
     * a voice assistant always listens), plays an assistant's voice or a call, or the phone is
     * in a call mode. A usage the player itself plays as is ignored.
     */
    fun watchVolume(
        playingUsages: Set<Int>,
        audioMode: Int,
        othersRecording: Boolean,
        duck: Float = DEFAULT_DUCK,
        ownUsage: Int = AudioAttributes.USAGE_MEDIA,
    ): Float {
        // Android does not say which app a player belongs to, so this player's own kind of
        // sound cannot count as someone else talking.
        val othersTalking = playingUsages.any { it in talking && it != ownUsage }
        return if (othersRecording || audioMode in callModes || othersTalking) duck else 1f
    }

    fun react(mode: Mode, change: Int, duck: Float = DEFAULT_DUCK): Reaction {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            return Reaction(1f, pause = false, note = "focus back; full volume")
        }
        val asked = when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "asked to duck"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "asked to pause for a while"
            AudioManager.AUDIOFOCUS_LOSS -> "asked to pause for good"
            else -> "unexpected change ${name(change)}"
        }
        return when (mode) {
            Mode.SELF_DUCK -> Reaction(duck, pause = false, note = "$asked; ducked itself")
            Mode.SYSTEM_DUCK -> Reaction(1f, pause = false, note = "$asked; playing on, any duck is Android's")
            Mode.WATCH -> Reaction(1f, pause = false, note = "$asked; but this mode holds no focus")
        }
    }

    fun name(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "GAIN_TRANSIENT_EXCLUSIVE"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "UNKNOWN($change)"
    }
}
