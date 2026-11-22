package org.claudevoice.mobile.app

import org.claudevoice.mobile.core.NoticeTap

/**
 * What a tap on the hidden-notice line does (ADR-CVM-003). It watches whether the screen paused or
 * lost the top place while a request from the line was open, and leaves the choice to the core's
 * rule, NoticeTap. [ask] asks Android for the permission; [openSettings] opens the app's
 * notification settings.
 */
class NoticeLine(private val ask: () -> Unit, private val openSettings: () -> Unit) {
    private var asking = false
    private var shown = false
    private var refusals = 0

    /** Asks first; before Android 13 there is no request, so the settings open. */
    fun tap(canAsk: Boolean) {
        if (!canAsk) return openSettings()
        asking = true
        shown = false
        ask()
    }

    /** The screen paused or lost the top place: a request from the line, if one is open, was shown. */
    fun leftScreen() {
        if (asking) shown = true
    }

    /** Android's answer to a request from the line; any other answer is ignored. */
    fun answered(granted: Boolean) {
        if (!asking) return
        asking = false
        val after = NoticeTap.after(granted, shown, refusals)
        refusals = NoticeTap.refusalsInRow(after, refusals)
        if (after == NoticeTap.After.OpenSettings) openSettings()
    }

    /** The state to keep while Android rebuilds the screen, as turning it does. */
    fun saved(): IntArray = intArrayOf(if (asking) 1 else 0, if (shown) 1 else 0, refusals)

    /** Takes the state [saved] gave before the screen was rebuilt; null or a damaged one is ignored. */
    fun restore(saved: IntArray?) {
        if (saved?.size != SAVED_SIZE) return
        asking = saved[0] == 1
        shown = saved[1] == 1
        refusals = saved[2]
    }

    companion object {
        private const val SAVED_SIZE = 3

        /** Whether the line shows: only while the listener runs with its notice hidden. */
        fun shows(listening: Boolean, noticesAllowed: Boolean) = NoticeTap.lineShows(listening, noticesAllowed)
    }
}
