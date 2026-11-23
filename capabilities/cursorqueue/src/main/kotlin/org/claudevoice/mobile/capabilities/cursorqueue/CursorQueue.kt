package org.claudevoice.mobile.capabilities.cursorqueue

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** One item an answer gives, with its id: `<install>-<number>`, the same each time it is given. */
class Given<T>(val id: String, val value: T)

/** How a plugin writes one of its items; [CursorQueue.read] is given the matching reader. */
fun interface ItemWriter<T> {
    fun write(out: DataOutputStream, value: T)
}

/** One answer of a poll, before the plugin puts it into its answer's JSON. */
class Page<T>(val items: List<Given<T>>, val cursor: String, val more: Boolean, val dropped: Int, val reset: Boolean)

/**
 * A poll's queue: items, oldest first, until the hub has acknowledged them (ADR-CVM-007,
 * ADR-CVM-010). It knows nothing of what an item holds; [at] says when each was kept.
 *
 * An item leaves only when a call passes back the cursor of an answer that gave it, so an answer
 * lost on the way is given again with the same ids. Past [MOST_KEPT] items, or [LONGEST_KEPT],
 * the oldest is dropped and counted. The count is given with each answer until the cursor of an
 * answer that carried it comes back. Drops are kept as a running total, and an acknowledgement
 * only raises how much of it the hub has: two answers that carried one count, both acknowledged,
 * take it off once. A cursor this queue never gave says reset and starts at the oldest kept.
 */
class CursorQueue<T> private constructor(
    private val chance: Chance,
    private val at: (T) -> Instant,
    private val numbers: Numbers,
    private val drops: Drops,
    private val entries: ArrayDeque<Entry<T>>,
    private val given: LinkedHashMap<String, Answered>,
) {
    private class Entry<T>(val number: Long, val value: T)

    /** The install part every id starts with, and the number the next item gets. */
    private class Numbers(val install: String, var next: Long)

    /** Every item dropped so far, and how many of them the hub has acknowledged. */
    private class Drops(var ever: Int, var seen: Int)

    /** What an answer gave: items up to [last], and the running total of drops when it was made. */
    private class Answered(val last: Long, val droppedEver: Int)

    /** How many items wait. */
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
     * Count [count] items lost outside the queue, such as in a damaged file beside it, as
     * dropped: the hub hears of them as of any drop, and may hear of too many, never too few.
     */
    fun lost(count: Int) {
        require(count >= 0) { "a loss is never negative" }
        if (count == 0) return
        drops.ever += count
        changed = true
    }

    /** Keep [value] last, and drop what is now too old or too many. */
    fun add(value: T, now: Instant) {
        entries.addLast(Entry(numbers.next, value))
        numbers.next += 1
        changed = true
        dropOld(now)
        while (entries.size > MOST_KEPT) drop()
    }

    /** The answer to a poll with [cursor]: passing a cursor acknowledges the answer that gave it. */
    fun since(cursor: String?, now: Instant): Page<T> {
        dropOld(now)
        val acked = if (cursor != null && given.containsKey(cursor)) cursor else null
        val reset = cursor != null && acked == null
        if (acked != null) acknowledge(acked, given.getValue(acked))
        val items = ArrayList<Given<T>>()
        for (entry in entries) {
            if (items.size == MOST_PER_ANSWER) break
            items += Given("${numbers.install}-${entry.number}", entry.value)
        }
        if (items.isEmpty() && dropped == 0 && acked != null) {
            return Page(items, acked, false, 0, false)
        }
        // With nothing to give, nothing is waiting either, so the answer covers every number so far.
        val last = if (items.isEmpty()) numbers.next - 1 else entries.elementAt(items.size - 1).number
        return Page(items, remember(last), entries.size > items.size, dropped, reset)
    }

    /** The queue to [out], each item by [item] after its number; [read] reads it back. */
    fun write(out: DataOutputStream, item: ItemWriter<T>) {
        out.writeUTF(numbers.install)
        out.writeLong(numbers.next)
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
            item.write(out, entry.value)
        }
    }

    // Wherever an old item sits: a phone whose clock was wrong can file one out of order.
    private fun dropOld(now: Instant) {
        val oldest = now.minus(LONGEST_KEPT)
        val each = entries.iterator()
        while (each.hasNext()) {
            if (at(each.next().value).isBefore(oldest)) {
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
    private fun acknowledge(cursor: String, answer: Answered) {
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

    /** A cursor never given before, for an answer that gave up to [last]. */
    private fun remember(last: Long): String {
        var cursor = chance.cursor()
        while (given.containsKey(cursor)) cursor = chance.cursor()
        given[cursor] = Answered(last, drops.ever)
        changed = true
        if (given.size > CURSORS_REMEMBERED) given.remove(given.keys.first())
        return cursor
    }

    companion object {
        /** ADR-CVM-010's limits. */
        const val MOST_KEPT = 500
        const val MOST_PER_ANSWER = 50

        /** How many answers' cursors the queue keeps; an older one reads as never given. */
        const val CURSORS_REMEMBERED = 16
        val LONGEST_KEPT: Duration = Duration.ofDays(7)

        /** A queue for a new install, or after the app's data was cleared. */
        fun <T> fresh(chance: Chance, at: (T) -> Instant) = afterLostKey(0, chance, at)

        /** A new queue that counts the [lost] items a lost key took with it as dropped. */
        fun <T> afterLostKey(lost: Int, chance: Chance, at: (T) -> Instant) =
            CursorQueue(chance, at, Numbers(chance.installPart(), 1), Drops(lost, 0), ArrayDeque(), LinkedHashMap())

        /**
         * A queue read back from [write], each item by [item]; an [IOException] when the bytes
         * end too soon. What an item's bytes may hold is [item]'s to check.
         */
        @Throws(IOException::class)
        fun <T> read(
            input: DataInputStream,
            chance: Chance,
            at: (T) -> Instant,
            item: (DataInputStream) -> T,
        ): CursorQueue<T> {
            val numbers = Numbers(input.readUTF(), input.readLong())
            val drops = Drops(input.readInt(), input.readInt())
            val given = LinkedHashMap<String, Answered>()
            var cursors = input.readInt()
            while (cursors > 0) {
                given[input.readUTF()] = Answered(input.readLong(), input.readInt())
                cursors -= 1
            }
            val entries = ArrayDeque<Entry<T>>()
            var kept = input.readInt()
            while (kept > 0) {
                kept -= 1
                val number = input.readLong()
                entries.addLast(Entry(number, item(input)))
            }
            return CursorQueue(chance, at, numbers, drops, entries, given)
        }
    }
}
