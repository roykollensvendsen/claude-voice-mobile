package org.claudevoice.mobile.plugins.sms_send.rules

import java.io.IOException

/**
 * What the outbox keeps of each confirmed text (ADR-CVM-010): the [journal], and whether the phone
 * has a default subscription to send with ([subscribed]). An outcome or a marker the journal could
 * not save is logged on the phone with [failed]; a marker left on disk still becomes an outcome at
 * the next start.
 */
class Tracking(
    private val journal: Journal,
    private val subscribed: () -> Boolean,
    private val failed: (String) -> Unit,
) {
    /** Whether an armed text goes to Android, or ends unsent or stopped before it. */
    enum class Go { SEND, UNSENT, STOPPED }

    /** Mark the confirmed text with [token], to [recipients] people, as held; false when that is not on disk. */
    fun held(token: String, recipients: Int): Boolean =
        try {
            journal.held(token, recipients)
            true
        } catch (_: IOException) {
            false
        }

    /** The owner stopped the text with [token] during its undo delay. */
    fun stopped(token: String) = stored { journal.endedAll(token, Reason.BY_OWNER) }

    /** Android refused the text with [token] to the recipient at [position]. */
    fun refused(token: String, position: Int) = stored { journal.ended(token, position, Reason.REFUSED) }

    /**
     * Whether the text with [token] may go now its delay is over: unsent with no default
     * subscription, stopped when its sending marker is not on disk, else sent.
     */
    fun going(token: String): Go {
        if (!subscribed()) {
            stored { journal.endedAll(token, Reason.NO_SUBSCRIPTION) }
            return Go.UNSENT
        }
        return try {
            journal.sending(token)
            Go.SEND
        } catch (_: IOException) {
            failed(NOT_MARKED)
            Go.STOPPED
        }
    }

    private fun stored(write: () -> Unit) {
        try {
            write()
        } catch (_: IOException) {
            failed(NOT_STORED)
        }
    }

    private companion object {
        const val NOT_MARKED = "the phone could not save the send, so it did not send it"
        const val NOT_STORED = "the phone could not save how a text ended"
    }
}
