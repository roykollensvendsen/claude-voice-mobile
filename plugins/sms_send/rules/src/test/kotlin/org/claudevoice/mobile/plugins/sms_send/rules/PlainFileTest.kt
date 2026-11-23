package org.claudevoice.mobile.plugins.sms_send.rules

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * The outcome file and the marker file (ADR-CVM-010): plain, written whole or not at all, and a
 * damaged one moved aside under a timed name, never overwritten, with only the last few kept.
 */
class PlainFileTest {
    @TempDir
    lateinit var folder: File

    private val synced = mutableListOf<File>()

    private fun file(name: String = "send-outcomes") = PlainFile(File(folder, name)) { synced += it }

    private val at = Instant.parse("2026-11-23T19:40:00Z")

    @Test
    fun `a file not yet written reads as none`() {
        assertNull(file().read())
    }

    @Test
    fun `a write replaces the file whole, leaves nothing beside it, and syncs the folder`() {
        val file = file()
        file.write(byteArrayOf(1, 2, 3))
        file.write(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), file.read())
        assertEquals(listOf("send-outcomes"), folder.list()!!.toList())
        assertEquals(2, synced.size)
        assertEquals(folder.absoluteFile, synced.last().absoluteFile)
    }

    @Test
    fun `a damaged file is copied aside under a timed name, never overwritten, and the folder synced`() {
        val file = file()
        file.write(byteArrayOf(9, 9))
        synced.clear()
        file.setAside(at)
        assertArrayEquals(byteArrayOf(9, 9), file.read(), "the file stays until the caller replaces it")
        val aside = File(folder, "send-outcomes.damaged-${at.toEpochMilli()}")
        assertArrayEquals(byteArrayOf(9, 9), aside.readBytes())
        assertEquals(1, synced.size)
        file.write(byteArrayOf(1))
        assertArrayEquals(byteArrayOf(9, 9), aside.readBytes(), "the new file never touches the one set aside")
    }

    @Test
    fun `only the last three set aside are kept, and another file's are never touched`() {
        val other = file("send-markers")
        other.write(byteArrayOf(7))
        other.setAside(at.minusSeconds(100))
        val file = file()
        for (second in listOf(5L, 1L, 4L, 2L, 3L)) {
            file.write(byteArrayOf(second.toByte()))
            file.setAside(at.plusSeconds(second))
        }
        val kept = folder.list()!!.filter { it.startsWith("send-outcomes.damaged-") }.sorted()
        assertEquals(listOf(3L, 4L, 5L).map { "send-outcomes.damaged-${at.plusSeconds(it).toEpochMilli()}" }, kept)
        assertTrue(File(folder, "send-markers.damaged-${at.minusSeconds(100).toEpochMilli()}").exists())
    }

    @Test
    fun `two set aside in the same millisecond each keep their own name`() {
        val file = file()
        file.write(byteArrayOf(1))
        file.setAside(at)
        file.write(byteArrayOf(2))
        file.setAside(at)
        val names = folder.list()!!.filter { it.startsWith("send-outcomes.damaged-") }.sorted()
        assertEquals(listOf(0L, 1L).map { "send-outcomes.damaged-${at.toEpochMilli() + it}" }, names)
        assertArrayEquals(byteArrayOf(2), File(folder, names[1]).readBytes())
    }

    @Test
    fun `setting aside a file that is not there changes nothing`() {
        val file = file()
        file.setAside(at)
        assertEquals(listOf<String>(), folder.list()!!.toList())
    }

    @Test
    fun `a removed file reads as none, and removing it again is no fault`() {
        val file = file()
        file.write(byteArrayOf(1))
        file.remove()
        assertNull(file.read())
        file.remove()
        assertFalse(File(folder, "send-outcomes").exists())
        assertEquals(3, synced.size, "a write and each removal sync the folder")
    }
}
