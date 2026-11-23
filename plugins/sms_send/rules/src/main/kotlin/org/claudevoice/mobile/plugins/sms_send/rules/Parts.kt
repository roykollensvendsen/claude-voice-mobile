package org.claudevoice.mobile.plugins.sms_send.rules

/**
 * Android's reports for one recipient's text, which goes as [count] parts (ADR-CVM-010,
 * "Delivered"). The text is sent only when every part is sent, failed when any part fails, and
 * delivered only when every part has a delivered report. Each outcome is told once; nothing is
 * told after a failure, and a text already told delivered is never told merely sent after it.
 */
class Parts(private val count: Int) {
    init {
        require(count >= 1) { "a text has at least one part" }
    }

    private val sent = HashSet<Int>()
    private val delivered = HashSet<Int>()
    private var failed = false
    private var sentTold = false
    private var deliveredTold = false

    /** Whether no report can change what was told: the text failed or was delivered. */
    val done: Boolean get() = failed || deliveredTold

    /** What the sent report of [part] with Android's [code] settles, or null when nothing new. */
    fun sent(part: Int, code: Int): Reason? {
        val reason = Reason.sent(code)
        return when {
            ignored(part) -> null
            reason != Reason.HANDED -> failure(reason)
            else -> handed(part)
        }
    }

    /** What the delivery report of [part] with the network's [status] settles, or null when nothing new. */
    fun delivered(part: Int, status: Int): Reason? =
        when (val reason = if (ignored(part)) null else Reason.delivered(status)) {
            null -> null
            Reason.REPORTED_DELIVERED -> reached(part)
            else -> failure(reason)
        }

    private fun ignored(part: Int) = done || part !in 0 until count

    private fun handed(part: Int): Reason? {
        sent += part
        if (sent.size < count || sentTold) return null
        sentTold = true
        return Reason.HANDED
    }

    private fun reached(part: Int): Reason? {
        delivered += part
        if (delivered.size < count) return null
        deliveredTold = true
        return Reason.REPORTED_DELIVERED
    }

    private fun failure(reason: Reason): Reason {
        failed = true
        return reason
    }
}
