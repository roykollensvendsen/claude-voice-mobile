package org.claudevoice.mobile.plugins.sms.rules

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

/** Where the queue's random parts come from: the shared queue's (ADR-CVM-007). */
typealias Chance = org.claudevoice.mobile.capabilities.cursorqueue.Chance

/** The real [Chance], from the platform's strong random source. */
typealias SecureChance = org.claudevoice.mobile.capabilities.cursorqueue.SecureChance

/** A message as the queue keeps it: masked before it was ever kept (ADR-CVM-010). */
data class Kept(
    val id: String,
    val from: String,
    val fromKind: Senders.Kind,
    val text: String,
    val cut: Boolean,
    val masked: Boolean,
    val at: OffsetDateTime,
)

/** One answer of `sms_since`, before it is put into the answer's JSON. */
class Batch(val messages: List<Kept>, val cursor: String, val more: Boolean, val dropped: Int, val reset: Boolean)


/**
 * ADR-CVM-010's queue: new text messages, oldest first, until the hub has acknowledged them.
 *
 * The ids, cursors, acknowledgement, resend, drops and reset are the shared [CursorQueue]'s
 * (ADR-CVM-007): a message leaves only when a call passes back the cursor of an answer that gave
 * it, and past [MOST_KEPT] messages, or [LONGEST_KEPT], the oldest is dropped and counted. What is
 * here is the message's own: masked before it is kept, its sender cleaned, its text cut.
 */
class SmsQueue private constructor(private val queue: CursorQueue<Message>) {
    private class Message(
        val from: Senders.Heard,
        val text: String,
        val cut: Boolean,
        val masked: Boolean,
        val at: OffsetDateTime,
    ) {
        fun kept(id: String) = Kept(id, from.from, from.kind, text, cut, masked, at)
    }

    /** How many messages wait. */
    val size: Int get() = queue.size

    /** Mark the queue as changed again, when what [takeChanged] reported could not be saved. */
    fun markChanged() = queue.markChanged()

    /** Whether the queue changed since this was last asked, so that it must be saved. */
    fun takeChanged(): Boolean = queue.takeChanged()

    /**
     * Keep a new message, masked first, and drop what is now too old or too many. The sender is
     * kept as [Senders.heard] gives it, with [toE164] for a national number; whether the message
     * is hidden is decided on the sender as it came. A kept text is cut to [LONGEST_TEXT].
     */
    fun add(from: String, body: String, at: OffsetDateTime, now: Instant, toE164: (String) -> String?) {
        val heard = Senders.heard(from, toE164)
        val masked = OneTimeCodes.shown(from, body) is Shown.Hidden
        val count = body.codePointCount(0, body.length)
        val cut = !masked && count > LONGEST_TEXT
        val text = when {
            masked -> OneTimeCodes.line(heard.from)
            cut -> body.substring(0, body.offsetByCodePoints(0, LONGEST_TEXT))
            else -> body
        }
        queue.add(Message(heard, text, cut, masked, at), now)
    }

    /** The answer to `sms_since(cursor)`: passing a cursor acknowledges the answer that gave it. */
    fun since(cursor: String?, now: Instant): Batch {
        val page = queue.since(cursor, now)
        return Batch(page.items.map { it.value.kept(it.id) }, page.cursor, page.more, page.dropped, page.reset)
    }

    /** The queue as bytes, to be sealed and kept; [fromBytes] reads them back. */
    fun toBytes(): ByteArray {
        // In memory, unbuffered: nothing to flush or close.
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(FORMAT)
        queue.write(out) { to, message ->
            writeText(to, message.from.from)
            writeText(to, message.text)
            to.writeBoolean(message.cut)
            to.writeBoolean(message.masked)
            to.writeByte(message.from.kind.ordinal)
            to.writeLong(message.at.toEpochSecond())
            to.writeInt(message.at.nano)
            to.writeInt(message.at.offset.totalSeconds)
        }
        return bytes.toByteArray()
    }

    companion object {
        /** ADR-CVM-010's limits; the shared queue's but for the text's length. */
        const val MOST_KEPT = CursorQueue.MOST_KEPT
        const val MOST_PER_ANSWER = CursorQueue.MOST_PER_ANSWER
        const val LONGEST_TEXT = 2000

        /** How many answers' cursors the phone keeps; an older one reads as never given. */
        const val CURSORS_REMEMBERED = CursorQueue.CURSORS_REMEMBERED
        val LONGEST_KEPT: Duration = CursorQueue.LONGEST_KEPT

        // UTF-8 takes at most four bytes for each of a kept text's characters.
        private const val LONGEST_TEXT_BYTES = LONGEST_TEXT * 4

        // 2 added each message's kind and whether its text was cut, and keeps drops as a total.
        private const val FORMAT = 2

        private val AT = { message: Message -> message.at.toInstant() }

        /** A queue for a new install, or after the app's data was cleared. */
        fun fresh(chance: Chance) = afterLostKey(0, chance)

        /** A new queue that counts the [lost] messages a lost key took with it as dropped. */
        fun afterLostKey(lost: Int, chance: Chance) = SmsQueue(CursorQueue.afterLostKey(lost, chance, AT))

        /**
         * A queue read back from [toBytes]; an [IOException] when the bytes are not one, whatever
         * is wrong with them, so that a damaged queue is never more than a lost one.
         */
        @Throws(IOException::class)
        fun fromBytes(bytes: ByteArray, chance: Chance): SmsQueue =
            try {
                read(DataInputStream(ByteArrayInputStream(bytes)), chance)
            } catch (wrong: DateTimeException) {
                throw IOException("a time that cannot be", wrong)
            } catch (wrong: ArithmeticException) {
                throw IOException("a time that cannot be", wrong)
            }

        private fun read(input: DataInputStream, chance: Chance): SmsQueue {
            if (input.readInt() != FORMAT) throw IOException("not a queue of this format")
            return SmsQueue(CursorQueue.read(input, chance, AT, ::message))
        }

        private fun message(input: DataInputStream): Message {
            val from = readText(input)
            val text = readText(input)
            val cut = input.readBoolean()
            val masked = input.readBoolean()
            val kind = kind(input.readByte().toInt())
            val second = input.readLong()
            val nano = input.readInt()
            val offset = ZoneOffset.ofTotalSeconds(input.readInt())
            val at = OffsetDateTime.ofInstant(Instant.ofEpochSecond(second, nano.toLong()), offset)
            return Message(Senders.Heard(from, kind), text, cut, masked, at)
        }

        // A text by its length in bytes: a long message joined from many parts can pass the
        // 64 KB that writeUTF allows.
        private fun writeText(out: DataOutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun kind(ordinal: Int): Senders.Kind {
            val kinds = Senders.Kind.entries
            if (ordinal !in kinds.indices) throw IOException("no sender kind $ordinal")
            return kinds[ordinal]
        }

        private fun readText(input: DataInputStream): String {
            val length = input.readInt()
            // No kept text is longer, so a damaged length never asks for gigabytes.
            if (length < 0 || length > LONGEST_TEXT_BYTES) throw IOException("a text of $length bytes")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
