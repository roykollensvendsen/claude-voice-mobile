package org.claudevoice.mobile.plugins.sms.rules

import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The queue as the receiver and `sms_since` share it (ADR-CVM-010). One lock covers both, and a
 * change is saved before the call returns. So a message handed in, an acknowledgement, and a
 * cursor handed out are all on disk before anyone hears of them, and survive the app being killed.
 * A call that changes nothing writes nothing, since the hub polls.
 */
class Inbox(private val file: QueueFile, chance: Chance, private val now: () -> Instant) {
    private val queue = file.load(chance)

    /** How many saves have failed since the last one that worked. */
    var failedInARow = 0
        private set

    /** Keep a new message; [toE164] puts a national number in E.164 form, or gives none. */
    @Synchronized
    @Throws(IOException::class)
    fun add(sender: String, body: String, at: OffsetDateTime, toE164: (String) -> String?) {
        queue.add(sender, body, at, now(), toE164)
        saved()
    }

    /**
     * A message from the receiver, kept at the phone's own time now in [zone]: the time a part
     * carries is the network's, which can be off by minutes. Whether it reached disk.
     */
    @Synchronized
    fun receive(sender: String, body: String, zone: ZoneId, toE164: (String) -> String?): Boolean {
        val at = now()
        queue.add(sender, body, OffsetDateTime.ofInstant(at, zone), at, toE164)
        // Tried twice: a message whose save fails and whose app is then killed is lost uncounted.
        return savedTrying() || savedTrying()
    }

    private fun savedTrying(): Boolean =
        try {
            saved()
            true
        } catch (_: IOException) {
            false
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
        if (queue.takeChanged()) save()
    }

    // A change that could not be saved stays marked, so the next call saves it.
    private fun save() {
        try {
            file.save(queue)
            failedInARow = 0
        } catch (failed: IOException) {
            queue.markChanged()
            failedInARow += 1
            throw failed
        }
    }
}
