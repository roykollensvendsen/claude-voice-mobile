package org.claudevoice.mobile.plugins.sms_send.rules

import java.io.IOException

/**
 * What the outbox keeps of each confirmed text (ADR-CVM-010): the [journal], whether the phone
 * has a default subscription to send with ([subscribed]), and whether it may still send
 * ([permitted]). An outcome or a marker the journal could not save is logged on the phone with
 * [failed]; a marker left on disk still becomes an outcome at the next start. With no journal,
 * since it could not be opened, no text is ever held, so the outbox sends nothing without its
 * marker.
 */
class Tracking(
    private val journal: Journal?,
    private val subscribed: () -> Boolean,
    private val permitted: () -> Boolean,
    private val failed: (String) -> Unit,
) {
    /** Whether an armed text goes to Android, or ends before it: failed, or stopped. */
    enum class Go { SEND, NO_SUBSCRIPTION, REFUSED, STOPPED }

    /** Whether the phone has a default subscription to send with now. */
    fun subscribed(): Boolean = subscribed.invoke()

    /** Mark the confirmed text with [token], to [recipients] people, as held; false when that is not on disk. */
    fun held(token: String, recipients: Int): Boolean {
        val journal = journal ?: return false
        return try {
            journal.held(token, recipients)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** The owner stopped the text with [token] during its undo delay. */
    fun stopped(token: String) = store(token, null, Reason.BY_OWNER)

    /** Android refused, or would refuse, the text with [token] to the recipient at [position]. */
    fun refused(token: String, position: Int) = store(token, position, Reason.REFUSED)

    /**
     * Whether the text with [token] may go now its delay is over. It fails with no default
     * subscription, or as refused by Android once sending was taken back, which is checked before
     * the sending mark so such a text never leaves one. It is stopped when its sending mark is not
     * on disk. Each way it ends here, every recipient gets that outcome and the marker goes.
     */
    fun going(token: String): Go {
        val go = when {
            !subscribed() -> Go.NO_SUBSCRIPTION
            !permitted() -> Go.REFUSED
            else -> marked(token)
        }
        val reason = when (go) {
            Go.SEND -> null
            Go.NO_SUBSCRIPTION -> Reason.NO_SUBSCRIPTION
            Go.REFUSED -> Reason.REFUSED
            Go.STOPPED -> Reason.UNSAVED
        }
        if (reason != null) store(token, null, reason)
        return go
    }

    private fun marked(token: String): Go {
        val journal = journal ?: return Go.STOPPED
        return try {
            journal.sending(token)
            Go.SEND
        } catch (_: IOException) {
            failed(NOT_MARKED)
            Go.STOPPED
        }
    }

    /** Keep [reason] for the recipient at [position], or for every one left when it is null. */
    private fun store(token: String, position: Int?, reason: Reason) {
        val journal = journal ?: return
        try {
            if (position == null) journal.endedAll(token, reason) else journal.ended(token, position, reason)
        } catch (_: IOException) {
            failed(NOT_STORED)
        }
    }

    private companion object {
        const val NOT_MARKED = "the phone could not save the send, so it did not send it"
        const val NOT_STORED = "the phone could not save how a text ended"
    }
}
