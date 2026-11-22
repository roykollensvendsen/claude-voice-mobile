package org.claudevoice.mobile.app

import org.claudevoice.mobile.core.NoticeTap

/**
 * What a tap on the hidden-notice line does (ADR-CVM-003). It reads whether this install asked
 * before ([askedBefore], the ask record) and Android's rationale flag ([flag]) just before the
 * request, reads the flag again after the answer, and leaves the choice to the core's rule,
 * NoticeTap. [ask] asks Android for the permission; [openSettings] opens the app's notification
 * settings.
 */
class NoticeLine(
    private val ask: () -> Unit,
    private val openSettings: () -> Unit,
    private val flag: () -> Boolean,
    private val askedBefore: () -> Boolean,
) {
    private var asking = false
    private var asked = false
    private var before = false
    private var refusals = 0

    /** Asks first; before Android 13 there is no request, so the settings open and nothing is read. */
    fun tap(canAsk: Boolean) {
        if (!canAsk) return openSettings()
        asked = askedBefore()
        before = flag()
        asking = true
        ask()
    }

    /** Android's answer to a request from the line; any other answer is ignored. */
    fun answered(granted: Boolean) {
        if (!asking) return
        asking = false
        val after = NoticeTap.after(granted, asked, before, flag(), refusals)
        refusals = NoticeTap.refusalsInRow(after, refusals)
        if (after == NoticeTap.After.OpenSettings) openSettings()
    }

    /** The state to keep while Android rebuilds the screen, as turning it does. */
    fun saved(): IntArray = intArrayOf(bit(asking), bit(asked), bit(before), refusals)

    /** Takes the state [saved] gave before the screen was rebuilt; null, damaged or older is ignored. */
    fun restore(saved: IntArray?) {
        if (saved?.size != SAVED_SIZE) return
        asking = saved[0] == 1
        asked = saved[1] == 1
        before = saved[2] == 1
        refusals = saved[SAVED_SIZE - 1]
    }

    private fun bit(on: Boolean) = if (on) 1 else 0

    companion object {
        private const val SAVED_SIZE = 4

        /** Whether the line shows: only while the listener runs with its notice hidden. */
        fun shows(listening: Boolean, noticesAllowed: Boolean) = NoticeTap.lineShows(listening, noticesAllowed)
    }
}
