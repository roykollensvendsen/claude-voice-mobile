package org.claudevoice.mobile.capabilities.fsync

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * An fsync on a folder. Whether the disk really kept the write cannot be seen from a test; what
 * can is that a folder is synced without harm, and that a folder that is not there fails loudly
 * rather than seeming saved.
 */
class FsyncTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a folder is synced, and what is in it stays`() {
        val folder = temporary.newFolder("outcomes")
        File(folder, "send-outcomes").writeText("kept")
        Fsync.folder(folder)
        assertTrue(File(folder, "send-outcomes").exists())
    }

    @Test
    fun `a folder that is not there fails, so a save is never taken as kept`() {
        val gone = File(temporary.root, "not-there")
        assertThrows(IOException::class.java) { Fsync.folder(gone) }
    }
}
