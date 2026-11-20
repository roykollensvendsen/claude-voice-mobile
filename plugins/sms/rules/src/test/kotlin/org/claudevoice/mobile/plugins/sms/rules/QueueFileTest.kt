package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.GeneralSecurityException
import java.time.OffsetDateTime

/** The queue kept in one sealed file (ADR-CVM-010). The key itself is the phone's Keystore's. */
class QueueFileTest {
    @TempDir
    lateinit var folder: File

    /** Seals by turning every byte; [lost] stands for a Keystore key that is gone. */
    private class Turned(var lost: Boolean = false) : Sealer {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun open(sealed: ByteArray): ByteArray {
            if (lost) throw GeneralSecurityException("the key is gone")
            return seal(sealed)
        }
    }

    private class Fixed(private val install: String) : Chance {
        private var cursors = 0

        override fun installPart() = install

        override fun cursor(): String {
            cursors += 1
            return "F" + cursors.toString().padStart(11, '0')
        }
    }

    private val at = OffsetDateTime.parse("2026-11-20T08:00:00+01:00")
    private val now = at.toInstant()

    private fun file() = File(folder, "sms-queue")

    // Each folder synced, with what the file held at that moment.
    private val synced = ArrayList<Pair<File, ByteArray>>()
    private val sync = FolderSync { synced += it to file().readBytes() }

    @Test
    fun `with no file yet the queue is new and empty`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        assertEquals(0, q.size)
        val batch = q.since(null, now)
        assertFalse(batch.reset)
        assertEquals(0, batch.dropped)
    }

    @Test
    fun `a saved queue is read back after a restart, with no half-written file left`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "Ses vi klokka seks?", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val back = QueueFile(file(), Turned(), sync).load(Fixed("inst0002"))
        val kept = back.since(null, now).messages.single()
        assertEquals("inst0001-1", kept.id)
        assertEquals("Ses vi klokka seks?", kept.text)
        assertEquals(listOf("sms-queue"), folder.list()!!.toList())
    }

    @Test
    fun `the file never holds a message's text in the clear`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "Ses vi klokka seks?", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val bytes = file().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("klokka"))
        assertFalse(bytes.contains("+4700000021"))
    }

    @Test
    fun `when the key is lost the queue starts again and counts every message it held as dropped`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now, noCountry)
        q.add("+4700000021", "to", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val back = QueueFile(file(), Turned(lost = true), sync).load(Fixed("inst0002"))
        assertEquals(0, back.size)
        back.add("+4700000021", "tre", at, now, noCountry)
        val batch = back.since(null, now)
        assertEquals(2, batch.dropped)
        assertEquals(listOf("inst0002-1"), batch.messages.map { it.id })
    }

    @Test
    fun `a lost key on a queue that held nothing counts nothing dropped`() {
        QueueFile(file(), Turned(), sync).save(QueueFile(file(), Turned(), sync).load(Fixed("inst0001")))
        val back = QueueFile(file(), Turned(lost = true), sync).load(Fixed("inst0007"))
        assertEquals(0, back.since(null, now).dropped)
    }

    @Test
    fun `a file whose start cannot be read starts a new queue that tells the hub some were lost`() {
        // Empty, not ours, or ours but cut before its count: the hub must not hear "none lost".
        val damaged = listOf(byteArrayOf(), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "SMSQ".toByteArray() + byteArrayOf(0))
        for (bytes in damaged) {
            file().writeBytes(bytes)
            val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0003"))
            assertEquals(0, q.size)
            val batch = q.since("Fold00000001", now)
            assertTrue(batch.dropped >= 1, bytes.size.toString())
            assertTrue(batch.reset, bytes.size.toString())
        }
    }

    @Test
    fun `a sealed file that opens to nonsense counts its messages as dropped`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val nonsense = object : Sealer {
            override fun seal(plain: ByteArray) = plain

            override fun open(sealed: ByteArray) = byteArrayOf(9, 9, 9)
        }
        val back = QueueFile(file(), nonsense, sync).load(Fixed("inst0004"))
        assertEquals(1, back.since(null, now).dropped)
    }

    @Test
    fun `a count outside the seal that cannot be true is held to one that can`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val good = file().readBytes()
        // The count is the four bytes after "SMSQ"; the lost key makes the seal unreadable.
        for ((held, dropped) in listOf(-5 to 1, Int.MIN_VALUE to 1, 100_000 to SmsQueue.MOST_KEPT)) {
            val bytes = good.copyOf()
            java.nio.ByteBuffer.wrap(bytes).putInt(4, held)
            file().writeBytes(bytes)
            val back = QueueFile(file(), Turned(lost = true), sync).load(Fixed("inst0006"))
            assertEquals(dropped, back.since(null, now).dropped, held.toString())
        }
    }

    @Test
    fun `after the new file is moved in, its folder is synced, so a power cut never brings back the old one`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        val (folderSynced, heldThen) = synced.single()
        assertEquals(folder.canonicalFile, folderSynced.canonicalFile)
        assertTrue(heldThen.contentEquals(file().readBytes()))
    }

    @Test
    fun `a new save replaces the old one whole`() {
        val q = QueueFile(file(), Turned(), sync).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now, noCountry)
        QueueFile(file(), Turned(), sync).save(q)
        q.since(q.since(null, now).cursor, now)
        QueueFile(file(), Turned(), sync).save(q)
        assertTrue(QueueFile(file(), Turned(), sync).load(Fixed("inst0005")).size == 0)
    }
}
