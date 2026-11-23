package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The outcomes and the crash markers on disk, as the plugin keeps them (ADR-CVM-010). A marker is
 * on disk before a send goes on; an outcome is on disk before its marker goes; a marker left at a
 * start becomes an outcome; a damaged file is set aside and counts at least one dropped, written
 * into the new file first; and a missing file counts none, since an empty marker file is removed.
 */
class JournalTest {
    @TempDir
    lateinit var folder: File

    private class Counted : Chance {
        private var cursors = 0

        override fun installPart() = "p4w8c1z6"

        override fun cursor(): String {
            cursors += 1
            return "Qm7Tz2pL" + cursors.toString().padStart(4, '0')
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-23T19:40:00Z"), ZoneOffset.ofHours(1))
    private var syncFails = false

    private fun file(name: String) = PlainFile(File(folder, name)) { if (syncFails) throw IOException("disk full") }

    private val outcomes get() = File(folder, "send-outcomes")
    private val markers get() = File(folder, "send-markers")

    private fun open() = Journal.open(file("send-outcomes"), file("send-markers"), Counted(), clock)

    private fun JsonObject.items() = getValue("outcomes").jsonArray.map { it.jsonObject }

    private fun JsonObject.words() = items().map {
        val item = it.getValue("recipient").jsonPrimitive.int.toString() + " " + it.getValue("reason").jsonPrimitive.content
        it.getValue("token").jsonPrimitive.content + " " + item
    }

    private fun JsonObject.dropped() = getValue("dropped").jsonPrimitive.int

    private fun onDisk() = Markers.fromBytes(markers.readBytes())

    @Test
    fun `a new install has no outcomes, drops nothing, and writes no marker file`() {
        val answer = open().since(null)
        assertEquals(listOf<String>(), answer.words())
        assertEquals(0, answer.dropped())
        assertFalse(markers.exists())
    }

    @Test
    fun `the marker is on disk when the hold starts, and says sending before the send goes on`() {
        val journal = open()
        journal.held("t-1", 2)
        assertEquals(2, onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC)).size)
        assertEquals(Reason.RESTARTED, onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC))[0].reason)
        journal.sending("t-1")
        assertEquals(Reason.MAYBE_SENT, onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC))[0].reason)
    }

    @Test
    fun `an outcome is on disk before its recipient leaves the marker, and the empty marker file is removed`() {
        val journal = open()
        journal.held("t-1", 2)
        journal.sending("t-1")
        journal.ended("t-1", 2, Reason.HANDED)
        val open = onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC)).map { it.recipient }
        assertEquals(listOf(1), open, "recipient 2 left the marker once its outcome was stored")
        val restarted = open()
        assertFalse(markers.exists(), "the restart turned the open recipient into an outcome and removed the file")
        assertEquals(
            listOf("t-1 2 handed to the network", "t-1 1 it may have been sent; check the text app"),
            restarted.since(null).words(),
        )
    }

    @Test
    fun `the last recipient's outcome removes the marker file`() {
        val journal = open()
        journal.held("t-1", 1)
        journal.sending("t-1")
        journal.ended("t-1", 1, Reason.NO_SERVICE)
        assertFalse(markers.exists())
        assertEquals(listOf("t-1 1 no service"), journal.since(null).words())
    }

    @Test
    fun `a stop during the undo stores stopped by the owner for each recipient, then drops the marker`() {
        val journal = open()
        journal.held("t-1", 2)
        journal.held("t-2", 1)
        journal.endedAll("t-1", Reason.BY_OWNER)
        assertEquals(1, onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC)).size, "t-2 is still held")
        val restarted = open().since(null).words()
        assertEquals(listOf("t-1 1 stopped by the owner", "t-1 2 stopped by the owner"), restarted.take(2), "on disk")
    }

    @Test
    fun `markers left at a start become outcomes at the phone's time, held as stopped and sending as unknown`() {
        val journal = open()
        journal.held("t-1", 1)
        journal.held("t-2", 1)
        journal.sending("t-2")
        val answer = open().since(null)
        assertEquals(
            listOf("t-1 1 the app restarted while it was held", "t-2 1 it may have been sent; check the text app"),
            answer.words(),
        )
        assertEquals("2026-11-23T20:40:00+01:00", answer.items()[0].getValue("at").jsonPrimitive.content)
        assertFalse(markers.exists())
    }

    @Test
    fun `a damaged outcome file is set aside, and the drop is on disk in the new file before anything else`() {
        open().apply {
            held("t-1", 1)
            sending("t-1")
            ended("t-1", 1, Reason.HANDED)
        }
        outcomes.writeBytes(byteArrayOf(0, 0, 0, 9, 1))
        open()
        val aside = folder.list()!!.single { it.startsWith("send-outcomes.damaged-") }
        assertEquals("send-outcomes.damaged-${clock.millis()}", aside)
        val answer = open().since(null)
        assertTrue(answer.dropped() >= 1, "a restart after the restart still counts the loss")
        assertEquals(listOf<String>(), answer.words())
    }

    @Test
    fun `a damaged marker file is set aside and counts a drop, and the outcomes kept stay`() {
        open().apply {
            held("t-1", 1)
            sending("t-1")
            ended("t-1", 1, Reason.HANDED)
            held("t-2", 1)
        }
        markers.writeBytes(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1))
        val answer = open().since(null)
        assertTrue(answer.dropped() >= 1)
        assertEquals(listOf("t-1 1 handed to the network"), answer.words())
        assertTrue(folder.list()!!.any { it.startsWith("send-markers.damaged-") })
        assertFalse(markers.exists())
    }

    @Test
    fun `a missing outcome or marker file counts none`() {
        open().apply {
            held("t-1", 1)
            sending("t-1")
            ended("t-1", 1, Reason.HANDED)
        }
        assertFalse(markers.exists())
        assertEquals(0, open().since(null).dropped())
        outcomes.delete()
        assertEquals(0, open().since(null).dropped())
    }

    @Test
    fun `an answer is on disk before it is given, and one that cannot be is refused`() {
        val journal = open()
        journal.held("t-1", 1)
        journal.sending("t-1")
        journal.ended("t-1", 1, Reason.HANDED)
        val cursor = journal.since(null).getValue("cursor").jsonPrimitive.content
        // A folder where the new file is written makes the write fail before the file is replaced.
        val blocker = File(folder, "send-outcomes.new").apply { mkdir() }
        assertThrows<IOException> { journal.since(cursor) }
        blocker.delete()
        // A stop for a text with no marker changes nothing, so it saves only what the failed save kept.
        journal.endedAll("t-none", Reason.BY_OWNER)
        assertEquals(listOf<String>(), open().since(null).words(), "the acknowledgement is on disk now")
    }

    @Test
    fun `a marker that cannot be saved is not taken as held, so the caller does not go on`() {
        val journal = open()
        syncFails = true
        assertThrows<IOException> { journal.held("t-1", 1) }
        assertThrows<IOException> { journal.sending("t-1") }
        syncFails = false
        journal.held("t-2", 1)
        val held = onDisk().leftAtRestart(clock.instant().atOffset(ZoneOffset.UTC)).map { it.token }
        assertEquals(listOf("t-2"), held, "the marker that never reached disk was not kept")
    }
}
