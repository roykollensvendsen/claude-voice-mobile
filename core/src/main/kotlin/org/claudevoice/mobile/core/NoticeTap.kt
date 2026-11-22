package org.claudevoice.mobile.core

/**
 * The line on the main screen when the owner has refused notifications, and what a tap on it does
 * next (ADR-CVM-003). A tap always asks for the permission first; this rule decides from what the
 * app saw while it asked. The app only feeds it: whether the screen paused or lost the top place
 * between the ask and the answer (so Android showed a request), whether it was granted, and the
 * refusals in a row so far. No time is guessed.
 */
object NoticeTap {
    /** What follows an answer to a request from the line. */
    enum class After {
        /** Granted: the line goes. */
        Granted,

        /** Refused after the request showed, for the first time in a row: the next tap asks again. */
        AskAgain,

        /** Android did not ask, or this is a second refusal in a row: open the app's notification settings. */
        OpenSettings,
    }

    fun after(granted: Boolean, shown: Boolean, refusalsInRow: Int): After = when {
        granted -> After.Granted
        shown && refusalsInRow == 0 -> After.AskAgain
        else -> After.OpenSettings
    }

    /** The refusals in a row after [after]. */
    fun refusalsInRow(after: After, refusalsInRow: Int): Int =
        if (after == After.AskAgain) refusalsInRow + 1 else 0

    /** The line shows only while the listener runs and Android hides its notice. */
    fun lineShows(listening: Boolean, noticesAllowed: Boolean): Boolean = listening && !noticesAllowed
}
