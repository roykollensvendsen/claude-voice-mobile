package org.claudevoice.prototype.audiofocus

import android.media.AudioManager

/** Who lowers the music when another app takes the sound. */
enum class Mode(val label: String) {
    /** The player lowers its own volume, and asks Android not to duck it. */
    SELF_DUCK("self"),

    /** The player does nothing, and leaves any ducking to Android. */
    SYSTEM_DUCK("system"),
}

/** What the player does with its volume after a focus change, and what it notes. */
data class Reaction(val volume: Float, val pause: Boolean, val note: String)

/**
 * The prototype's whole behaviour on a focus change. It never pauses: the question is
 * what Android asks for and what Roy hears, so a request to pause is logged, not obeyed.
 */
object FocusPolicy {
    const val DUCKED_VOLUME = 0.2f

    fun willPauseWhenDucked(mode: Mode): Boolean = mode == Mode.SELF_DUCK

    fun react(mode: Mode, change: Int): Reaction {
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
            Mode.SELF_DUCK -> Reaction(DUCKED_VOLUME, pause = false, note = "$asked; ducked itself")
            Mode.SYSTEM_DUCK -> Reaction(1f, pause = false, note = "$asked; playing on, any duck is Android's")
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
