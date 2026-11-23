package org.claudevoice.mobile.capabilities.cursorqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * A poll's queue (ADR-CVM-007, ADR-CVM-010): items wait in the order they came until a later
 * call passes back the cursor of the answer that gave them. The items here are plain notes, since
 * the queue never looks inside one.
 */
class CursorQueueTest {
    private data class Note(val text: String, val at: Instant)

    /** Install parts and cursors counted up, so each test knows exactly what it gets. */
    private class Counted(private val install: String = "inst0001") : Chance {
        private var cursors = 0

        override fun installPart() = install

        override fun cursor(): String {
            cursors += 1
            return "C" + cursors.toString().padStart(11, '0')
        }
    }

    private val now = Instant.parse("2026-11-20T07:00:00Z")

    private fun queue(install: String = "inst0001") = CursorQueue.fresh(Counted(install), Note::at)

    private fun CursorQueue<Note>.note(text: String, at: Instant = now) = add(Note(text, at), now)

    private fun Page<Note>.ids() = items.map { it.id }

    private fun Page<Note>.texts() = items.map { it.value.text }

    private fun CursorQueue<Note>.bytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        write(DataOutputStream(bytes)) { out, note ->
            out.writeUTF(note.text)
            out.writeLong(note.at.epochSecond)
        }
        return bytes.toByteArray()
    }

    private fun back(bytes: ByteArray, chance: Chance = Counted()) =
        CursorQueue.read(DataInputStream(ByteArrayInputStream(bytes)), chance, Note::at) {
            Note(it.readUTF(), Instant.ofEpochSecond(it.readLong()))
        }

    @Test
    fun `items come oldest first, each with an id of the install part and a number`() {
        val q = queue()
        q.note("en")
        q.note("to")
        q.note("tre")
        val page = q.since(null, now)
        assertEquals(listOf("inst0001-1", "inst0001-2", "inst0001-3"), page.ids())
        assertEquals(listOf("en", "to", "tre"), page.texts())
        assertFalse(page.reset)
        assertFalse(page.more)
        assertEquals(0, page.dropped)
        assertEquals(3, q.size)
    }

    @Test
    fun `passing a cursor back acknowledges its answer, and only later items follow`() {
        val q = queue()
        q.note("en")
        val first = q.since(null, now)
        q.note("to")
        val second = q.since(first.cursor, now)
        assertEquals(listOf("inst0001-2"), second.ids())
        assertNotEquals(first.cursor, second.cursor)
        assertEquals(1, q.size)
    }

    @Test
    fun `an answer lost on the way is given again, with the same ids and a new cursor`() {
        val q = queue()
        q.note("en")
        val before = q.since(null, now)
        q.note("to")
        q.note("tre")
        val lost = q.since(before.cursor, now)
        val again = q.since(before.cursor, now)
        assertEquals(lost.ids(), again.ids())
        assertEquals(listOf("inst0001-2", "inst0001-3"), again.ids())
        assertNotEquals(lost.cursor, again.cursor)
    }

    @Test
    fun `without a cursor the answer starts at the oldest item kept`() {
        val q = queue()
        q.note("en")
        q.since(null, now)
        assertEquals(listOf("inst0001-1"), q.since(null, now).ids())
    }

    @Test
    fun `an answer holds at most 50 items and says when more wait`() {
        val q = queue()
        for (i in 1..120) q.note("$i")
        val first = q.since(null, now)
        assertEquals(CursorQueue.MOST_PER_ANSWER, first.items.size)
        assertTrue(first.more)
        val second = q.since(first.cursor, now)
        assertEquals("inst0001-51", second.items.first().id)
        assertTrue(second.more)
        val third = q.since(second.cursor, now)
        assertEquals(20, third.items.size)
        assertFalse(third.more)
    }

    @Test
    fun `exactly 50 waiting fit one answer with nothing more`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_PER_ANSWER) q.note("$i")
        val page = q.since(null, now)
        assertEquals(CursorQueue.MOST_PER_ANSWER, page.items.size)
        assertFalse(page.more)
    }

    @Test
    fun `past 500 kept the oldest is dropped and counted`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT + 3) q.note("$i")
        val page = q.since(null, now)
        assertEquals(3, page.dropped)
        assertEquals("inst0001-4", page.items.first().id)
        assertEquals(CursorQueue.MOST_KEPT, q.size)
    }

    @Test
    fun `exactly 500 kept drops nothing`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT) q.note("$i")
        assertEquals(0, q.since(null, now).dropped)
        assertEquals(CursorQueue.MOST_KEPT, q.size)
    }

    @Test
    fun `an item older than 7 days is dropped and counted, and one exactly 7 days old is kept`() {
        val q = queue()
        q.note("for gammel", now)
        q.note("akkurat sju dager", now.plusSeconds(1))
        q.note("ny", now.plus(Duration.ofDays(6)))
        val later = now.plusSeconds(1).plus(CursorQueue.LONGEST_KEPT)
        val page = q.since(null, later)
        assertEquals(1, page.dropped)
        assertEquals(listOf("akkurat sju dager", "ny"), page.texts())
    }

    @Test
    fun `old items are dropped when a new one comes, too, wherever they sit`() {
        val q = queue()
        q.note("ny", now)
        q.note("feil klokke", now.minus(Duration.ofDays(8)))
        assertEquals(1, q.size)
        val eightDays = now.plus(Duration.ofDays(8))
        q.add(Note("enda nyere", eightDays), eightDays)
        assertEquals(1, q.size)
    }

    @Test
    fun `a dropped count is given again until the answer that carried it is acknowledged`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT + 3) q.note("$i")
        val carried = q.since(null, now)
        assertEquals(3, q.since(null, now).dropped)
        assertEquals(0, q.since(carried.cursor, now).dropped)
    }

    @Test
    fun `a count is still given after its answer is lost and an older cursor is passed`() {
        val q = queue()
        q.note("en")
        val older = q.since(null, now)
        q.note("gammel", now.minus(Duration.ofDays(8)))
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
        for (i in 1..CursorQueue.MOST_KEPT + 3) q.note("$i")
        val carried = q.since(null, now)
        q.note("ny 1")
        q.note("ny 2")
        assertEquals(2, q.since(carried.cursor, now).dropped)
    }

    @Test
    fun `two answers that carried one count take it off once, acknowledged in turn`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT + 3) q.note("$i")
        val first = q.since(null, now)
        val retry = q.since(null, now)
        assertEquals(3, retry.dropped)
        assertEquals(0, q.since(first.cursor, now).dropped)
        assertEquals(0, q.since(retry.cursor, now).dropped)
        q.note("gammel", now.minus(Duration.ofDays(8)))
        assertEquals(1, q.since(null, now).dropped)
    }

    @Test
    fun `an answer acknowledged again never takes its count off twice`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT + 2) q.note("$i")
        val carried = q.since(null, now)
        q.since(carried.cursor, now)
        q.note("gammel", now.minus(Duration.ofDays(8)))
        assertEquals(1, q.since(carried.cursor, now).dropped)
    }

    @Test
    fun `an empty answer with nothing dropped gives back the cursor it was passed`() {
        val q = queue()
        q.note("en")
        val first = q.since(null, now)
        val empty = q.since(first.cursor, now)
        assertTrue(empty.items.isEmpty())
        assertEquals(first.cursor, empty.cursor)
        assertFalse(empty.more)
        assertEquals(first.cursor, q.since(empty.cursor, now).cursor)
    }

    @Test
    fun `an answer with nothing to give still has a cursor`() {
        val page = queue().since(null, now)
        assertTrue(page.items.isEmpty())
        assertEquals("C00000000001", page.cursor)
        assertFalse(page.reset)
        assertFalse(page.more)
    }

    @Test
    fun `the cursor of an empty answer never acknowledges an item that came after it`() {
        val q = queue()
        val empty = q.since(null, now)
        q.note("en")
        assertEquals(listOf("inst0001-1"), q.since(empty.cursor, now).ids())
    }

    @Test
    fun `a cursor this queue never gave says reset and starts at the oldest kept`() {
        val q = queue()
        q.note("en")
        q.note("to")
        val page = q.since("Zz9fremmed00", now)
        assertTrue(page.reset)
        assertEquals(listOf("inst0001-1", "inst0001-2"), page.ids())
    }

    @Test
    fun `a cursor from another install says reset, with ids of this install`() {
        val old = queue("inst0001")
        old.note("en")
        val oldCursor = old.since(null, now).cursor
        val fresh = queue("inst0002")
        fresh.note("to")
        val page = fresh.since(oldCursor, now)
        assertTrue(page.reset)
        assertEquals(listOf("inst0002-1"), page.ids())
    }

    @Test
    fun `once a later cursor is acknowledged, an earlier one is a cursor never given`() {
        val q = queue()
        for (i in 1..CursorQueue.MOST_KEPT + 3) q.note("$i")
        val earlier = q.since(null, now)
        val later = q.since(earlier.cursor, now)
        q.since(later.cursor, now)
        val stale = q.since(earlier.cursor, now)
        assertTrue(stale.reset)
        assertEquals(0, stale.dropped)
    }

    @Test
    fun `the queue remembers its last 16 cursors, and an older one is a cursor never given`() {
        val (first, second) = List(2) { rememberingAll() }
        assertTrue(first.first.since(first.second[0], now).reset)
        assertFalse(second.first.since(second.second[1], now).reset)
    }

    private fun rememberingAll(): Pair<CursorQueue<Note>, List<String>> {
        val q = queue()
        val cursors = mutableListOf<String>()
        for (i in 1..CursorQueue.CURSORS_REMEMBERED + 1) {
            q.note("ny $i")
            cursors += q.since(null, now).cursor
        }
        return q to cursors
    }

    @Test
    fun `a cursor already given is never given again, even when the random source repeats one`() {
        val repeats = object : Chance {
            private val cursors = ArrayDeque(listOf("C1", "C1", "C2"))

            override fun installPart() = "inst0001"

            override fun cursor() = cursors.removeFirst()
        }
        val q = CursorQueue.fresh(repeats, Note::at)
        q.note("en")
        val first = q.since(null, now)
        val second = q.since(null, now)
        assertEquals("C1", first.cursor)
        assertEquals("C2", second.cursor)
    }

    @Test
    fun `after a lost key the next answer counts the lost items as dropped, with new ids`() {
        val q = CursorQueue.afterLostKey(7, Counted("inst0009"), Note::at)
        q.note("ny")
        val page = q.since("C00000000001", now)
        assertEquals(7, page.dropped)
        assertTrue(page.reset)
        assertEquals(listOf("inst0009-1"), page.ids())
    }

    @Test
    fun `a queue read back goes on where it was, with its cursors and their counts`() {
        val q = queue()
        q.note("gammel", now.minus(Duration.ofDays(8)))
        q.note("en")
        q.note("to")
        val given = q.since(null, now)
        val back = back(q.bytes())
        assertEquals(q.size, back.size)
        val read = back.since(null, now)
        assertEquals(q.since(null, now).texts(), read.texts())
        assertEquals(1, read.dropped)
        back.note("etter")
        val next = back.since(given.cursor, now)
        assertFalse(next.reset)
        assertEquals(listOf("inst0001-4"), next.ids())
        assertEquals(0, next.dropped)
    }

    @Test
    fun `bytes that end too soon are not a queue`() {
        val q = queue()
        q.note("en")
        q.since(null, now)
        val good = q.bytes()
        for (cut in listOf(0, 5, good.size - 1)) {
            assertThrows<EOFException>("cut at $cut") { back(good.copyOf(cut)) }
        }
    }

    @Test
    fun `a change is reported once, and again when it could not be saved`() {
        val q = queue()
        assertFalse(q.takeChanged())
        q.note("en")
        assertTrue(q.takeChanged())
        assertFalse(q.takeChanged())
        q.markChanged()
        assertTrue(q.takeChanged())
    }

    @Test
    fun `an answer is a change, as it remembers its cursor, and so is an acknowledgement`() {
        val q = queue()
        q.note("en")
        q.takeChanged()
        val first = q.since(null, now)
        assertTrue(q.takeChanged())
        q.since(first.cursor, now)
        assertTrue(q.takeChanged())
        val empty = q.since(null, now)
        q.since(empty.cursor, now)
        assertTrue(q.takeChanged(), "acknowledging a later cursor forgets the earlier ones")
        q.since(empty.cursor, now)
        assertFalse(q.takeChanged(), "an empty answer that gives back its cursor changes nothing")
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
}
