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

    @Test
    fun `with no file yet the queue is new and empty`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        assertEquals(0, q.size)
        assertFalse(q.since(null, now).reset)
    }

    @Test
    fun `a saved queue is read back after a restart, with no half-written file left`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        q.add("+4700000021", "Ses vi klokka seks?", at, now)
        QueueFile(file(), Turned()).save(q)
        val back = QueueFile(file(), Turned()).load(Fixed("inst0002"))
        val kept = back.since(null, now).messages.single()
        assertEquals("inst0001-1", kept.id)
        assertEquals("Ses vi klokka seks?", kept.text)
        assertEquals(listOf("sms-queue"), folder.list()!!.toList())
    }

    @Test
    fun `the file never holds a message's text in the clear`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        q.add("+4700000021", "Ses vi klokka seks?", at, now)
        QueueFile(file(), Turned()).save(q)
        val bytes = file().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("klokka"))
        assertFalse(bytes.contains("+4700000021"))
    }

    @Test
    fun `when the key is lost the queue starts again and counts every message it held as dropped`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now)
        q.add("+4700000021", "to", at, now)
        QueueFile(file(), Turned()).save(q)
        val back = QueueFile(file(), Turned(lost = true)).load(Fixed("inst0002"))
        assertEquals(0, back.size)
        back.add("+4700000021", "tre", at, now)
        val batch = back.since(null, now)
        assertEquals(2, batch.dropped)
        assertEquals(listOf("inst0002-1"), batch.messages.map { it.id })
    }

    @Test
    fun `a damaged file starts a new queue rather than stopping the plugin`() {
        file().writeBytes(byteArrayOf(1, 2, 3))
        val q = QueueFile(file(), Turned()).load(Fixed("inst0003"))
        assertEquals(0, q.size)
        assertEquals(0, q.since(null, now).dropped)
    }

    @Test
    fun `a sealed file that opens to nonsense counts its messages as dropped`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now)
        QueueFile(file(), Turned()).save(q)
        val nonsense = object : Sealer {
            override fun seal(plain: ByteArray) = plain

            override fun open(sealed: ByteArray) = byteArrayOf(9, 9, 9)
        }
        val back = QueueFile(file(), nonsense).load(Fixed("inst0004"))
        assertEquals(1, back.since(null, now).dropped)
    }

    @Test
    fun `a new save replaces the old one whole`() {
        val q = QueueFile(file(), Turned()).load(Fixed("inst0001"))
        q.add("+4700000021", "en", at, now)
        QueueFile(file(), Turned()).save(q)
        q.since(q.since(null, now).cursor, now)
        QueueFile(file(), Turned()).save(q)
        assertTrue(QueueFile(file(), Turned()).load(Fixed("inst0005")).size == 0)
    }
}
