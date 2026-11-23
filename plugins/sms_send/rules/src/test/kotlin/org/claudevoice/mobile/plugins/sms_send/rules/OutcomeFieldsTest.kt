package org.claudevoice.mobile.plugins.sms_send.rules

import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.OffsetDateTime

/**
 * The outcome file and the crash marker hold only tokens, positions, times, outcomes and the fixed
 * reasons, and the marker's held or sending (ADR-CVM-010; voice-architect's f1 on #195). Each file
 * is read back field by field to its last byte, so nothing else can hide in it. Android's own
 * result codes become one of the fixed reasons, so no error text of Android's can reach a file
 * (quality-claude-voice's f2 on #195).
 */
class OutcomeFieldsTest {
    private class Fixed : Chance {
        override fun installPart() = "p4w8c1z6"

        override fun cursor() = "Qm7Tz2pL0aVd"
    }

    private val at = OffsetDateTime.parse("2026-11-22T14:02:21+01:00")

    @Test
    fun `the outcome file holds the queue's own fields and, for each outcome, a token, a position, a reason and a time`() {
        val log = OutcomeLog.fresh(Fixed())
        log.record(SendOutcome("t-9f3a1c", 2, Reason.NO_SERVICE, at), at.toInstant())
        log.since(null, at.toInstant())
        val read = DataInputStream(ByteArrayInputStream(log.toBytes()))
        assertEquals(OutcomeLog.FORMAT, read.readInt())
        assertEquals("p4w8c1z6", read.readUTF(), "the install part")
        assertEquals(2L, read.readLong(), "the next number")
        assertEquals(0, read.readInt(), "drops so far")
        assertEquals(0, read.readInt(), "drops acknowledged")
        assertEquals(1, read.readInt(), "cursors given")
        assertEquals("Qm7Tz2pL0aVd", read.readUTF())
        assertEquals(1L, read.readLong(), "the last number that answer gave")
        assertEquals(0, read.readInt(), "the drops it carried")
        assertEquals(1, read.readInt(), "outcomes kept")
        assertEquals(1L, read.readLong(), "the outcome's number")
        assertEquals("t-9f3a1c", read.readUTF(), "the token")
        assertEquals(2, read.readByte().toInt(), "the position")
        assertEquals("NO_SERVICE", read.readUTF(), "the reason, by its fixed name")
        assertEquals(at.toEpochSecond(), read.readLong())
        assertEquals(0, read.readInt(), "the time's nanoseconds")
        assertEquals(3600, read.readInt(), "the time's offset")
        assertEquals(-1, read.read(), "nothing after the last field")
    }

    @Test
    fun `the marker file holds, for each held text, a token, its stage, how many recipients and which are open, and a time`() {
        val markers = Markers.none()
        markers.held("t-9f3a1c", 3, at)
        markers.sending("t-9f3a1c")
        markers.stored("t-9f3a1c", 2)
        val read = DataInputStream(ByteArrayInputStream(markers.toBytes()))
        assertEquals(Markers.FORMAT, read.readInt())
        assertEquals(1, read.readInt(), "markers kept")
        assertEquals("t-9f3a1c", read.readUTF(), "the token")
        assertEquals("SENDING", read.readUTF(), "the stage, by its fixed name")
        assertEquals(2, read.readByte().toInt(), "recipients still open")
        assertEquals(1, read.readByte().toInt(), "the first open position")
        assertEquals(3, read.readByte().toInt(), "the second open position")
        assertEquals(at.toEpochSecond(), read.readLong())
        assertEquals(0, read.readInt(), "the time's nanoseconds")
        assertEquals(3600, read.readInt(), "the time's offset")
        assertEquals(-1, read.read(), "nothing after the last field")
    }

    @Test
    fun `every result code Android can give a sent report becomes one of the fixed reasons`() {
        assertEquals(Reason.HANDED, Reason.sent(SENT_OK))
        assertEquals(Reason.RADIO_OFF, Reason.sent(RADIO_OFF))
        assertEquals(Reason.NO_SERVICE, Reason.sent(NO_SERVICE))
        for (code in listOf(GENERIC_FAILURE, NULL_PDU, 0, 5, 42, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(Reason.REFUSED, Reason.sent(code), "code $code")
        }
    }

    @Test
    fun `a delivery report's status becomes delivered, failed, or nothing yet while the network still tries`() {
        for (status in listOf(0x00, 0x01, 0x02, 0x1F)) assertEquals(Reason.REPORTED_DELIVERED, Reason.delivered(status))
        for (status in listOf(0x20, 0x25, 0x3F)) assertEquals(null, Reason.delivered(status), "status $status")
        for (status in listOf(0x40, 0x41, 0x60, 0x7F, 0xFF, -1)) assertEquals(Reason.REPORTED_FAILED, Reason.delivered(status))
    }

    private companion object {
        // Android's own values: Activity.RESULT_OK, and SmsManager's RESULT_ERROR_* codes.
        const val SENT_OK = -1
        const val GENERIC_FAILURE = 1
        const val RADIO_OFF = 2
        const val NULL_PDU = 3
        const val NO_SERVICE = 4
    }
}
