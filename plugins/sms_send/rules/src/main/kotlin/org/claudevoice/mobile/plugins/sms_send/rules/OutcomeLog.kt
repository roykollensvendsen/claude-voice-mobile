package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.claudevoice.mobile.capabilities.cursorqueue.CursorQueue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * How one recipient's text ended: the request's [token], the recipient's [recipient] position in
 * the read-back, one of the fixed [reason]s, and when the phone learned it. Never a number, a name
 * or a text (ADR-CVM-010, "Delivered").
 */
data class SendOutcome(val token: String, val recipient: Int, val reason: Reason, val at: OffsetDateTime) {
    init {
        require(token.length in 1..LONGEST_TOKEN) { "a token is 1 to $LONGEST_TOKEN characters" }
        require(recipient in 1..MOST_RECIPIENTS) { "a recipient is a position from 1 to $MOST_RECIPIENTS" }
    }

    companion object {
        /** The answer schema's limits. */
        const val LONGEST_TOKEN = 64
        const val MOST_RECIPIENTS = 5
    }
}

/**
 * The outcomes `sms_send_outcomes` gives, oldest first, until the hub has acknowledged them. The
 * shared [CursorQueue] gives the ids, cursors, acknowledgement, resend, drops and reset, as for
 * `sms_since`. An outcome goes only when a call passes back the cursor of an answer that gave it,
 * or past [MOST_KEPT] or [LONGEST_KEPT], counted as dropped; a reset removes nothing.
 *
 * Its bytes are kept in a plain file, not sealed: they hold only the queue's own fields and, for
 * each outcome, a token, a position, a reason by its fixed name and a time (ADR-CVM-010).
 */
class OutcomeLog private constructor(private val queue: CursorQueue<SendOutcome>) {
    /** How many outcomes wait. */
    val size: Int get() = queue.size

    /** Keep [outcome], and drop what is now too old or too many. */
    fun record(outcome: SendOutcome, now: Instant) = queue.add(outcome, now)

    /** The answer to `sms_send_outcomes(cursor)`: passing a cursor acknowledges the answer that gave it. */
    fun since(cursor: String?, now: Instant): JsonObject {
        val page = queue.since(cursor, now)
        val items = page.items.map { item(it.id, it.value) }
        return JsonObject(
            mapOf(
                "outcomes" to JsonArray(items),
                "cursor" to JsonPrimitive(page.cursor),
                "more" to JsonPrimitive(page.more),
                "dropped" to JsonPrimitive(page.dropped),
                "reset" to JsonPrimitive(page.reset),
            ),
        )
    }

    /** Count [count] outcomes lost in a damaged file as dropped, so the hub hears of them. */
    fun lost(count: Int) = queue.lost(count)

    /** Mark the log as changed again, when what [takeChanged] reported could not be saved. */
    fun markChanged() = queue.markChanged()

    /** Whether the log changed since this was last asked, so that it must be saved. */
    fun takeChanged(): Boolean = queue.takeChanged()

    /** The log as bytes, for its plain file; [fromBytes] reads them back. */
    fun toBytes(): ByteArray {
        // In memory, unbuffered: nothing to flush or close.
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(FORMAT)
        queue.write(out) { to, outcome ->
            to.writeUTF(outcome.token)
            to.writeByte(outcome.recipient)
            to.writeUTF(outcome.reason.name)
            writeTime(to, outcome.at)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val FORMAT = 1
        const val MOST_KEPT = CursorQueue.MOST_KEPT
        val LONGEST_KEPT: Duration = CursorQueue.LONGEST_KEPT

        private val TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")
        private val AT = { outcome: SendOutcome -> outcome.at.toInstant() }

        /** A log for a new install, or after its file was lost. */
        fun fresh(chance: Chance) = OutcomeLog(CursorQueue.fresh(chance, AT))

        /**
         * A log read back from [toBytes]; an [IOException] when the bytes are not one, whatever is
         * wrong with them, so that a damaged file is never more than a lost one.
         */
        @Throws(IOException::class)
        fun fromBytes(bytes: ByteArray, chance: Chance): OutcomeLog {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != FORMAT) throw IOException("not an outcome log of this format")
            return OutcomeLog(CursorQueue.read(input, chance, AT, ::outcome))
        }

        private fun item(id: String, outcome: SendOutcome) = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "token" to JsonPrimitive(outcome.token),
                "recipient" to JsonPrimitive(outcome.recipient),
                "outcome" to JsonPrimitive(outcome.reason.outcome.word),
                "at" to JsonPrimitive(TIME.format(outcome.at)),
                "reason" to JsonPrimitive(outcome.reason.phrase),
            ),
        )

        // Whatever is wrong with a field, the bytes are no log.
        private fun outcome(input: DataInputStream): SendOutcome =
            try {
                SendOutcome(input.readUTF(), input.readByte().toInt(), Reason.valueOf(input.readUTF()), readTime(input))
            } catch (wrong: IllegalArgumentException) {
                throw IOException("no outcome", wrong)
            }

        /** A time as its second, nanosecond and offset, as the sms queue keeps one. */
        internal fun writeTime(out: DataOutputStream, at: OffsetDateTime) {
            out.writeLong(at.toEpochSecond())
            out.writeInt(at.nano)
            out.writeInt(at.offset.totalSeconds)
        }

        internal fun readTime(input: DataInputStream): OffsetDateTime =
            try {
                val second = input.readLong()
                val nano = input.readInt()
                val offset = ZoneOffset.ofTotalSeconds(input.readInt())
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(second, nano.toLong()), offset)
            } catch (wrong: DateTimeException) {
                throw IOException("a time that cannot be", wrong)
            }
    }
}
