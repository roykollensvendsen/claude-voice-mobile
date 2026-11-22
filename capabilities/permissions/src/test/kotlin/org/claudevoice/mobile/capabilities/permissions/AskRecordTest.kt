package org.claudevoice.mobile.capabilities.permissions

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRecordTest {
    private val dir: File = Files.createTempDirectory("ask-record").toFile()

    @After
    fun clean() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private companion object {
        const val READ = "android.permission.READ_SMS"
        const val CONTACTS = "android.permission.READ_CONTACTS"
    }

    @Test
    fun `an install from before the record has asked for nothing`() {
        assertFalse(AskRecord(dir).asked(READ))
    }

    @Test
    fun `a recorded ask is remembered, by a new reader too, and only for that permission`() {
        AskRecord(dir).record(READ)
        assertTrue(AskRecord(dir).asked(READ))
        assertFalse(AskRecord(dir).asked(CONTACTS))
    }

    @Test
    fun `recording twice keeps one line, and two permissions keep two`() {
        val record = AskRecord(dir)
        record.record(READ)
        record.record(READ)
        record.record(CONTACTS)
        assertEquals(listOf(READ, CONTACTS), File(dir, AskRecord.FILE).readLines())
    }

    @Test
    fun `a name with a line break is refused, so one ask can never write another`() {
        val record = AskRecord(dir)
        assertTrue(runCatching { record.record("$READ\n$CONTACTS") }.isFailure)
        assertFalse(record.asked(CONTACTS))
    }

    @Test
    fun `a record that cannot be written leaves the ask unrecorded and does not throw`() {
        val gone = File(dir, "missing/deeper")
        AskRecord(gone).record(READ)
        assertFalse(AskRecord(gone).asked(READ))
    }
}
