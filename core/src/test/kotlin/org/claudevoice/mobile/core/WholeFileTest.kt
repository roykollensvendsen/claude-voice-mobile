package org.claudevoice.mobile.core

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A file replaced whole, and then its folder written to the disk, so the rename survives a power cut. */
class WholeFileTest {
    /** A folder of its own for each test, which JUnit deletes afterwards. */
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "hub.json")

    @Test
    fun `after the rename, the folder that holds the file is written to the disk`() {
        val seen = mutableListOf<Pair<File, String>>()
        replaceWhole(file, "new".toByteArray(), { folder -> seen += folder to file.readText() })
        assertEquals(listOf(dir to "new"), seen)
    }

    @Test
    fun `a folder that cannot be written to the disk does not undo a write that is already in place`() {
        replaceWhole(file, "new".toByteArray(), { throw IOException("not supported here") })
        assertEquals("new", file.readText())
    }

    @Test
    fun `a write that fails before the rename never touches the folder`() {
        File(dir, "hub.json.new").apply { mkdir(); File(this, "x").writeText("") }
        val seen = mutableListOf<File>()
        org.junit.jupiter.api.assertThrows<IOException> { replaceWhole(file, "new".toByteArray(), { seen += it }) }
        assertEquals(emptyList<File>(), seen)
    }

    @Test
    fun `the real folder sync works on a real folder`() {
        replaceWhole(file, "new".toByteArray())
        assertEquals("new", file.readText())
        SYNC_FOLDER.sync(dir)
    }
}
