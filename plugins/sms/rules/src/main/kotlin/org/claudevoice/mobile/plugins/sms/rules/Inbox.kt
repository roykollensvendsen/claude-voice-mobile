package org.claudevoice.mobile.plugins.sms.rules

import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The queue as the receiver and `sms_since` share it (ADR-CVM-010). One lock covers both, and a
 * change is saved before the call returns. So a message handed in, an acknowledgement, and a
 * cursor handed out are all on disk before anyone hears of them, and survive the app being killed.
 * A call that changes nothing writes nothing, since the hub polls.
 */
class Inbox(private val file: QueueFile, chance: Chance, private val now: () -> Instant) {
    private val queue = file.load(chance)

    /** Keep a new message; [toE164] puts a national number in E.164 form, or gives none. */
    @Synchronized
    @Throws(IOException::class)
    fun add(sender: String, body: String, at: OffsetDateTime, toE164: (String) -> String?) {
        queue.add(sender, body, at, now(), toE164)
        saved()
    }

    /** The answer to `sms_since(cursor)`, kept on disk before it is given; throws when it cannot be. */
    @Synchronized
    @Throws(IOException::class)
    fun since(cursor: String?): Batch {
        val batch = queue.since(cursor, now())
        saved()
        return batch
    }

    private fun saved() {
        if (queue.takeChanged()) file.save(queue)
    }
}
