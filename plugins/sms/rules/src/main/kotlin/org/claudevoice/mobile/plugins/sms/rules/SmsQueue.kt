package org.claudevoice.mobile.plugins.sms.rules

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Where the queue's random parts come from: the real one is [SecureChance]. */
interface Chance {
    /** Eight lowercase letters or digits, chosen anew for each install and each lost key. */
    fun installPart(): String

    /** Twelve letters or digits, for an answer's cursor. */
    fun cursor(): String
}

/** The real [Chance], from the platform's strong random source. */
class SecureChance(private val random: SecureRandom = SecureRandom()) : Chance {
    override fun installPart() = pick(INSTALL_LETTERS, INSTALL_LENGTH)

    override fun cursor() = pick(CURSOR_LETTERS, CURSOR_LENGTH)

    private fun pick(letters: String, length: Int): String {
        val picked = StringBuilder(length)
        while (picked.length < length) picked.append(letters[random.nextInt(letters.length)])
        return picked.toString()
    }

    private companion object {
        const val INSTALL_LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val CURSOR_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val INSTALL_LENGTH = 8
        const val CURSOR_LENGTH = 12
    }
}

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
 * A message leaves only when a call passes back the cursor of an answer that gave it, so an
 * answer lost on the way is given again with the same ids. Past [MOST_KEPT] messages, or
 * [LONGEST_KEPT], the oldest is dropped and counted. The count is given with each answer until
 * the cursor of an answer that carried it comes back. Drops are kept as a running total, and an
 * acknowledgement only raises how much of it the hub has: two answers that carried one count,
 * both acknowledged, take it off once.
 */
class SmsQueue private constructor(
    private val chance: Chance,
    private val install: String,
    private var nextNumber: Long,
    private val drops: Drops,
    private val entries: ArrayDeque<Entry>,
    private val given: LinkedHashMap<String, Given>,
) {
    private class Entry(
        val number: Long,
        val from: Senders.Heard,
        val text: String,
        val cut: Boolean,
        val masked: Boolean,
        val at: OffsetDateTime,
    ) {
        fun kept(id: String) = Kept(id, from.from, from.kind, text, cut, masked, at)
    }

    /** Every message dropped so far, and how many of them the hub has acknowledged. */
    private class Drops(var ever: Int, var seen: Int)

    /** What an answer gave: messages up to [last], and the running total of drops when it was made. */
    private class Given(val last: Long, val droppedEver: Int)

    /** How many messages wait. */
    val size: Int get() = entries.size

    // Dropped and not yet known by the hub.
    private val dropped: Int get() = drops.ever - drops.seen

    // Whether anything worth keeping changed since the last [takeChanged].
    private var changed = false

    /** Mark the queue as changed again, when what [takeChanged] reported could not be saved. */
    fun markChanged() {
        changed = true
    }

    /** Whether the queue changed since this was last asked, so that it must be saved. */
    fun takeChanged(): Boolean {
        val was = changed
        changed = false
        return was
    }

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
        entries.addLast(Entry(nextNumber, heard, text, cut, masked, at))
        nextNumber += 1
        changed = true
        dropOld(now)
        while (entries.size > MOST_KEPT) drop()
    }

    /** The answer to `sms_since(cursor)`: passing a cursor acknowledges the answer that gave it. */
    fun since(cursor: String?, now: Instant): Batch {
        dropOld(now)
        val acked = if (cursor != null && given.containsKey(cursor)) cursor else null
        val reset = cursor != null && acked == null
        if (acked != null) acknowledge(acked, given.getValue(acked))
        val messages = ArrayList<Kept>()
        for (entry in entries) {
            if (messages.size == MOST_PER_ANSWER) break
            messages += entry.kept("$install-${entry.number}")
        }
        if (messages.isEmpty() && dropped == 0 && acked != null) {
            return Batch(messages, acked, false, 0, false)
        }
        // With nothing to give, nothing is waiting either, so the answer covers every number so far.
        val last = if (messages.isEmpty()) nextNumber - 1 else entries.elementAt(messages.size - 1).number
        return Batch(messages, remember(last), entries.size > messages.size, dropped, reset)
    }

    /** The queue as bytes, to be sealed and kept; [fromBytes] reads them back. */
    fun toBytes(): ByteArray {
        // In memory, unbuffered: nothing to flush or close.
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(FORMAT)
        out.writeUTF(install)
        out.writeLong(nextNumber)
        out.writeInt(drops.ever)
        out.writeInt(drops.seen)
        out.writeInt(given.size)
        for ((cursor, answer) in given) {
            out.writeUTF(cursor)
            out.writeLong(answer.last)
            out.writeInt(answer.droppedEver)
        }
        out.writeInt(entries.size)
        for (entry in entries) {
            out.writeLong(entry.number)
            writeText(out, entry.from.from)
            writeText(out, entry.text)
            out.writeBoolean(entry.cut)
            out.writeBoolean(entry.masked)
            out.writeByte(entry.from.kind.ordinal)
            out.writeLong(entry.at.toEpochSecond())
            out.writeInt(entry.at.nano)
            out.writeInt(entry.at.offset.totalSeconds)
        }
        return bytes.toByteArray()
    }

    // Wherever an old message sits: a phone whose clock was wrong can file one out of order.
    private fun dropOld(now: Instant) {
        val oldest = now.minus(LONGEST_KEPT)
        val each = entries.iterator()
        while (each.hasNext()) {
            if (each.next().at.toInstant().isBefore(oldest)) {
                each.remove()
                drops.ever += 1
                changed = true
            }
        }
    }

    private fun drop() {
        entries.removeFirst()
        drops.ever += 1
    }

    /** Delete what the answer gave, mark the drops it told of as known, and forget every earlier cursor. */
    private fun acknowledge(cursor: String, answer: Given) {
        while (entries.isNotEmpty() && entries.first().number <= answer.last) {
            entries.removeFirst()
            changed = true
        }
        if (answer.droppedEver > drops.seen) {
            drops.seen = answer.droppedEver
            changed = true
        }
        val earlier = ArrayList<String>()
        for (key in given.keys) {
            if (key == cursor) break
            earlier += key
        }
        for (key in earlier) given.remove(key)
        if (earlier.isNotEmpty()) changed = true
    }

    /** A cursor never given before, for an answer that gave up to [last] and carried [dropped]. */
    private fun remember(last: Long): String {
        var cursor = chance.cursor()
        while (given.containsKey(cursor)) cursor = chance.cursor()
        given[cursor] = Given(last, drops.ever)
        changed = true
        if (given.size > CURSORS_REMEMBERED) given.remove(given.keys.first())
        return cursor
    }

    companion object {
        /** ADR-CVM-010's limits. */
        const val MOST_KEPT = 500
        const val MOST_PER_ANSWER = 50
        const val LONGEST_TEXT = 2000

        /** How many answers' cursors the phone keeps; an older one reads as never given. */
        const val CURSORS_REMEMBERED = 16
        val LONGEST_KEPT: Duration = Duration.ofDays(7)

        // UTF-8 takes at most four bytes for each of a kept text's characters.
        private const val LONGEST_TEXT_BYTES = LONGEST_TEXT * 4

        // 2 added each message's kind and whether its text was cut, and keeps drops as a total.
        private const val FORMAT = 2

        /** A queue for a new install, or after the app's data was cleared. */
        fun fresh(chance: Chance) = afterLostKey(0, chance)

        /** A new queue that counts the [lost] messages a lost key took with it as dropped. */
        fun afterLostKey(lost: Int, chance: Chance) =
            SmsQueue(chance, chance.installPart(), 1, Drops(lost, 0), ArrayDeque(), LinkedHashMap())

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
            val install = input.readUTF()
            val nextNumber = input.readLong()
            val drops = Drops(input.readInt(), input.readInt())
            val given = LinkedHashMap<String, Given>()
            var cursors = input.readInt()
            while (cursors > 0) {
                given[input.readUTF()] = Given(input.readLong(), input.readInt())
                cursors -= 1
            }
            val entries = ArrayDeque<Entry>()
            var kept = input.readInt()
            while (kept > 0) {
                kept -= 1
                val number = input.readLong()
                val from = readText(input)
                val text = readText(input)
                val cut = input.readBoolean()
                val masked = input.readBoolean()
                val kind = kind(input.readByte().toInt())
                val second = input.readLong()
                val nano = input.readInt()
                val offset = ZoneOffset.ofTotalSeconds(input.readInt())
                val at = OffsetDateTime.ofInstant(Instant.ofEpochSecond(second, nano.toLong()), offset)
                entries.addLast(Entry(number, Senders.Heard(from, kind), text, cut, masked, at))
            }
            return SmsQueue(chance, install, nextNumber, drops, entries, given)
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
