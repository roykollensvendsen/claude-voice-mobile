package org.claudevoice.mobile.plugins.sms_send.rules

import java.io.IOException

/**
 * Android's sent and delivered reports for each part of each recipient's text (ADR-CVM-010,
 * "Delivered"), turned into that recipient's outcomes in the [journal]. Only texts handed over
 * since the app started are followed, and only the latest [FOLLOWED] of them, so reports that
 * never come cannot fill the phone; a report for any other text changes nothing, and a text
 * handed over before a restart is already told as "it may have been sent". An outcome the journal
 * could not save is logged with [failed]; its marker stays, and the next start reports it.
 */
class Reports(private val journal: Journal, private val failed: (String) -> Unit) {
    private val texts = object : LinkedHashMap<Pair<String, Int>, Parts>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, Parts>) = size > FOLLOWED
    }

    /** The text with [token] to the recipient at [position] goes as [parts] parts; follow their reports. */
    @Synchronized
    fun expect(token: String, position: Int, parts: Int) {
        texts[token to position] = Parts(parts)
    }

    /** Android's sent report, with result [code], for [part] of that recipient's text. */
    @Synchronized
    fun sent(token: String, position: Int, part: Int, code: Int) = settle(token, position) { it.sent(part, code) }

    /** A delivery report for [part], with the network's [status]; null when the phone cannot read it. */
    @Synchronized
    fun delivered(token: String, position: Int, part: Int, status: Int?) {
        if (status != null) settle(token, position) { it.delivered(part, status) }
    }

    private fun settle(token: String, position: Int, report: (Parts) -> Reason?) {
        val parts = texts[token to position] ?: return
        val reason = report(parts) ?: return
        if (parts.done) texts.remove(token to position)
        try {
            journal.ended(token, position, reason)
        } catch (_: IOException) {
            failed(NOT_STORED)
        }
    }

    private companion object {
        const val FOLLOWED = 100
        const val NOT_STORED = "the phone could not save how a text ended"
    }
}
