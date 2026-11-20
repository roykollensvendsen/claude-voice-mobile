package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The queue of ADR-CVM-010: messages wait in arrival order until a later call passes back the
 * cursor of the answer that gave them. Every sender, number and text here is made up.
 */
class SmsQueueTest {
    /** Install parts and cursors counted up, so each test knows exactly what it gets. */
    private class Counted(private val install: String = "inst0001") : Chance {
        private var cursors = 0

        override fun installPart() = install

        override fun cursor(): String {
            cursors += 1
            return "C" + cursors.toString().padStart(11, '0')
        }
    }

    private val start = OffsetDateTime.parse("2026-11-20T08:00:00+01:00")
    private val now = start.toInstant()

    private fun queue(install: String = "inst0001") = SmsQueue.fresh(Counted(install))

    private fun SmsQueue.addFriend(text: String, at: OffsetDateTime = start) = add("+4700000021", text, at, now)

    private fun Batch.ids() = messages.map { it.id }

    @Test
    fun `messages come oldest first, each with an id of the install part and a number`() {
        val q = queue()
        q.addFriend("en")
        q.addFriend("to")
        q.addFriend("tre")
        val batch = q.since(null, now)
        assertEquals(listOf("inst0001-1", "inst0001-2", "inst0001-3"), batch.ids())
        assertEquals(listOf("en", "to", "tre"), batch.messages.map { it.text })
        assertFalse(batch.reset)
        assertFalse(batch.more)
        assertEquals(0, batch.dropped)
    }

    @Test
    fun `a message keeps its sender and the time it arrived, with its offset`() {
        val q = queue()
        val at = OffsetDateTime.parse("2026-11-20T07:59:58-03:30")
        q.add("Testbutikk", "Pakken er sendt", at, now)
        val kept = q.since(null, now).messages.single()
        assertEquals("Testbutikk", kept.from)
        assertEquals(at, kept.at)
    }

    @Test
    fun `a message that may hold a code is kept only as its line, never as its text`() {
        val q = queue()
        q.add("Eksempelbank", "Engangskoden din er 482913", start, now)
        q.addFriend("Ses i kveld?")
        val (code, friend) = q.since(null, now).messages
        assertEquals("a one-time code from Eksempelbank", code.text)
        assertTrue(code.masked)
        assertEquals("Ses i kveld?", friend.text)
        assertFalse(friend.masked)
    }

    @Test
    fun `passing a cursor back acknowledges its answer, and only later messages follow`() {
        val q = queue()
        q.addFriend("en")
        val first = q.since(null, now)
        q.addFriend("to")
        val second = q.since(first.cursor, now)
        assertEquals(listOf("inst0001-2"), second.ids())
        assertNotEquals(first.cursor, second.cursor)
    }

    @Test
    fun `an answer lost on the way is given again, with the same ids and a new cursor`() {
        val q = queue()
        q.addFriend("en")
        val before = q.since(null, now)
        q.addFriend("to")
        q.addFriend("tre")
        val lost = q.since(before.cursor, now)
        val again = q.since(before.cursor, now)
        assertEquals(lost.ids(), again.ids())
        assertEquals(listOf("inst0001-2", "inst0001-3"), again.ids())
        assertNotEquals(lost.cursor, again.cursor)
    }

    @Test
    fun `without a cursor the answer starts at the oldest message kept`() {
        val q = queue()
        q.addFriend("en")
        q.since(null, now)
        assertEquals(listOf("inst0001-1"), q.since(null, now).ids())
    }

    @Test
    fun `an answer holds at most 50 messages and says when more wait`() {
        val q = queue()
        for (i in 1..120) q.addFriend("melding $i")
        val first = q.since(null, now)
        assertEquals(SmsQueue.MOST_PER_ANSWER, first.messages.size)
        assertTrue(first.more)
        val second = q.since(first.cursor, now)
        assertEquals("inst0001-51", second.messages.first().id)
        assertTrue(second.more)
        val third = q.since(second.cursor, now)
        assertEquals(20, third.messages.size)
        assertFalse(third.more)
    }

    @Test
    fun `exactly 50 waiting fit one answer with nothing more`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_PER_ANSWER) q.addFriend("melding $i")
        val batch = q.since(null, now)
        assertEquals(SmsQueue.MOST_PER_ANSWER, batch.messages.size)
        assertFalse(batch.more)
    }

    @Test
    fun `past 500 kept the oldest is dropped and counted`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT + 3) q.addFriend("melding $i")
        val batch = q.since(null, now)
        assertEquals(3, batch.dropped)
        assertEquals("inst0001-4", batch.messages.first().id)
    }

    @Test
    fun `exactly 500 kept drops nothing`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT) q.addFriend("melding $i")
        assertEquals(0, q.since(null, now).dropped)
        assertEquals(SmsQueue.MOST_KEPT, q.size)
    }

    @Test
    fun `a message older than 7 days is dropped and counted, and one exactly 7 days old is kept`() {
        val q = queue()
        q.addFriend("for gammel", start)
        q.addFriend("akkurat sju dager", start.plusSeconds(1))
        q.addFriend("ny", start.plusDays(6))
        val later = start.plusSeconds(1).toInstant().plus(SmsQueue.LONGEST_KEPT)
        val batch = q.since(null, later)
        assertEquals(1, batch.dropped)
        assertEquals(listOf("akkurat sju dager", "ny"), batch.messages.map { it.text })
    }

    @Test
    fun `old messages are dropped when a new one arrives, too`() {
        val q = queue()
        q.addFriend("gammel", start)
        q.add("+4700000021", "ny", start.plusDays(8), start.toInstant().plus(Duration.ofDays(8)))
        assertEquals(1, q.size)
    }

    @Test
    fun `a dropped count is given again until the answer that carried it is acknowledged`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT + 3) q.addFriend("melding $i")
        val carried = q.since(null, now)
        assertEquals(3, q.since(null, now).dropped)
        assertEquals(0, q.since(carried.cursor, now).dropped)
    }

    @Test
    fun `a count is still given after its answer is lost and an older cursor is passed`() {
        val q = queue()
        q.addFriend("en")
        val older = q.since(null, now)
        q.addFriend("gammel", start.minusDays(8))
        val lost = q.since(older.cursor, now)
        assertEquals(1, lost.dropped)
        val again = q.since(older.cursor, now)
        assertEquals(1, again.dropped)
        assertNotEquals(lost.cursor, again.cursor)
        assertEquals(0, q.since(again.cursor, now).dropped)
    }

    @Test
    fun `drops after an answer stay counted when that answer is acknowledged`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT + 3) q.addFriend("melding $i")
        val carried = q.since(null, now)
        q.addFriend("ny 1")
        q.addFriend("ny 2")
        assertEquals(2, q.since(carried.cursor, now).dropped)
    }

    @Test
    fun `an empty answer with nothing dropped gives back the cursor it was passed`() {
        val q = queue()
        q.addFriend("en")
        val first = q.since(null, now)
        val empty = q.since(first.cursor, now)
        assertTrue(empty.messages.isEmpty())
        assertEquals(first.cursor, empty.cursor)
        assertEquals(first.cursor, q.since(empty.cursor, now).cursor)
    }

    @Test
    fun `an answer with nothing to give still has a cursor`() {
        val batch = queue().since(null, now)
        assertTrue(batch.messages.isEmpty())
        assertTrue(batch.cursor.isNotEmpty())
        assertFalse(batch.reset)
    }

    @Test
    fun `the cursor of an empty answer never acknowledges a message that came after it`() {
        val q = queue()
        val empty = q.since(null, now)
        q.addFriend("en")
        assertEquals(listOf("inst0001-1"), q.since(empty.cursor, now).ids())
    }

    @Test
    fun `a cursor this phone never gave says reset and starts at the oldest kept`() {
        val q = queue()
        q.addFriend("en")
        q.addFriend("to")
        val batch = q.since("Zz9fremmed00", now)
        assertTrue(batch.reset)
        assertEquals(listOf("inst0001-1", "inst0001-2"), batch.ids())
    }

    @Test
    fun `a cursor from another install says reset, with ids of this install`() {
        val old = queue("inst0001")
        old.addFriend("en")
        val oldCursor = old.since(null, now).cursor
        val fresh = queue("inst0002")
        fresh.addFriend("to")
        val batch = fresh.since(oldCursor, now)
        assertTrue(batch.reset)
        assertEquals(listOf("inst0002-1"), batch.ids())
    }

    @Test
    fun `once a later cursor is acknowledged, an earlier one is a cursor never given`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT + 3) q.addFriend("melding $i")
        val earlier = q.since(null, now)
        val later = q.since(earlier.cursor, now)
        q.since(later.cursor, now)
        val stale = q.since(earlier.cursor, now)
        assertTrue(stale.reset)
        assertEquals(0, stale.dropped)
    }

    @Test
    fun `the phone remembers its last 16 cursors, and an older one is a cursor never given`() {
        // Two queues built alike, since asking either one gives a cursor and forgets another.
        val (first, second) = List(2) { rememberingAll() }
        assertTrue(first.queue.since(first.cursors[0], now).reset)
        assertFalse(second.queue.since(second.cursors[1], now).reset)
    }

    private class Remembered(val queue: SmsQueue, val cursors: List<String>)

    private fun rememberingAll(): Remembered {
        val q = queue()
        val cursors = mutableListOf<String>()
        for (i in 1..SmsQueue.CURSORS_REMEMBERED + 1) {
            q.addFriend("ny $i")
            cursors += q.since(null, now).cursor
        }
        return Remembered(q, cursors)
    }

    @Test
    fun `after a lost key the next answer counts the lost messages as dropped, with new ids`() {
        val q = SmsQueue.afterLostKey(7, Counted("inst0009"))
        q.addFriend("ny")
        val batch = q.since("C00000000001", now)
        assertEquals(7, batch.dropped)
        assertTrue(batch.reset)
        assertEquals(listOf("inst0009-1"), batch.ids())
    }

    @Test
    fun `a queue read back from its bytes goes on where it was`() {
        val q = queue()
        q.addFriend("gammel", start.minusDays(8))
        q.addFriend("en")
        q.add("Eksempelbank", "Koden er 482913", OffsetDateTime.parse("2026-11-20T09:00:00.25+05:45"), now)
        val given = q.since(null, now)
        val back = SmsQueue.fromBytes(q.toBytes(), Counted())
        assertEquals(q.size, back.size)
        val read = back.since(null, now)
        assertEquals(q.since(null, now).messages, read.messages)
        assertEquals(1, read.dropped)
        assertTrue(read.messages.single { it.from == "Eksempelbank" }.masked)
        back.addFriend("etter")
        val next = back.since(given.cursor, now)
        assertFalse(next.reset)
        assertEquals(listOf("inst0001-4"), next.ids())
        assertEquals(0, next.dropped)
    }

    /** A random source that always picks the same place in the letters. */
    private class Always(private val pick: (Int) -> Int) : SecureRandom() {
        override fun nextInt(bound: Int) = pick(bound)
    }

    @Test
    fun `an install part is eight lowercase letters or digits, and a cursor twelve letters or digits`() {
        val first = SecureChance(Always { 0 })
        assertEquals("aaaaaaaa", first.installPart())
        assertEquals("AAAAAAAAAAAA", first.cursor())
        val last = SecureChance(Always { it - 1 })
        assertEquals("99999999", last.installPart())
        assertEquals("999999999999", last.cursor())
        val real = SecureChance()
        assertTrue(Regex("[a-z0-9]{8}").matches(real.installPart()))
        assertTrue(Regex("[A-Za-z0-9]{12}").matches(real.cursor()))
    }

    @Test
    fun `a queue read back remembers which cursors it gave and their counts`() {
        val q = queue()
        for (i in 1..SmsQueue.MOST_KEPT + 1) q.addFriend("melding $i")
        val given = q.since(null, now)
        val back = SmsQueue.fromBytes(q.toBytes(), Counted())
        val next = back.since(given.cursor, now)
        assertFalse(next.reset)
        assertEquals(0, next.dropped)
    }
}
