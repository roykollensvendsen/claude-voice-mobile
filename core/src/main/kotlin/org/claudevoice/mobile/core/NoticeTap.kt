package org.claudevoice.mobile.core

/**
 * The line on the main screen when the owner has refused notifications, and what a tap on it does
 * next (ADR-CVM-003). A tap always asks for the permission first; this rule decides from what the
 * app read around the ask. The app only feeds it: whether this install had asked before, by its own
 * ask record; Android's rationale flag just before the request and again after the answer; whether
 * it was granted; and the refusals in a row so far. No time is guessed, and the screen pausing is
 * not used, since Android 15 pauses it even when it refuses at once.
 */
object NoticeTap {
    /** What follows an answer to a request from the line. */
    enum class After {
        /** Granted: the line goes. */
        Granted,

        /** Refused after Android showed the request, for the first time in a row: the next tap asks again. */
        AskAgain,

        /** Android did not ask, or this is a second refusal in a row: open the app's notification settings. */
        OpenSettings,
    }

    fun after(granted: Boolean, askedBefore: Boolean, flagBefore: Boolean, flagAfter: Boolean, refusalsInRow: Int) =
        when {
            granted -> After.Granted
            refusalsInRow == 0 && asked(askedBefore, flagBefore, flagAfter) -> After.AskAgain
            else -> After.OpenSettings
        }

    /**
     * Whether Android showed the request. It did not only when the install asked before and the flag
     * was false both before and after: a request Android shows and the owner refuses turns the flag
     * true, or found it true.
     */
    private fun asked(askedBefore: Boolean, flagBefore: Boolean, flagAfter: Boolean) =
        !askedBefore || flagBefore || flagAfter

    /** The refusals in a row after [after]. */
    fun refusalsInRow(after: After, refusalsInRow: Int): Int =
        if (after == After.AskAgain) refusalsInRow + 1 else 0

    /** The line shows only while the listener runs and Android hides its notice. */
    fun lineShows(listening: Boolean, noticesAllowed: Boolean): Boolean = listening && !noticesAllowed
}
