package org.claudevoice.mobile.plugins.sms_send.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.OffsetDateTime

/**
 * The crash marker (ADR-CVM-010, "A held message survives nothing"): while a text is held, and
 * while it is handed to Android, a marker says which side of the send the app is on. A marker
 * left at the next start becomes an outcome for each recipient it still holds open. The tokens
 * here are made up.
 */
class MarkersTest {
    private val at = OffsetDateTime.parse("2026-11-22T14:02:11+01:00")
    private val later = at.plusSeconds(10)

    @Test
    fun `a marker left while held becomes stopped for each recipient, since nothing was handed to Android`() {
        val markers = Markers.none()
        markers.held("t-1", 3, at)
        assertEquals(
            listOf(1, 2, 3).map { SendOutcome("t-1", it, Reason.RESTARTED, later) },
            Markers.fromBytes(markers.toBytes()).leftAtRestart(later),
        )
    }

    @Test
    fun `a marker left while sending becomes unknown, and is never told as stopped`() {
        val markers = Markers.none()
        markers.held("t-1", 2, at)
        markers.sending("t-1")
        assertEquals(
            listOf(1, 2).map { SendOutcome("t-1", it, Reason.MAYBE_SENT, later) },
            Markers.fromBytes(markers.toBytes()).leftAtRestart(later),
        )
    }

    @Test
    fun `a recipient whose outcome is stored leaves the marker, and the marker goes with the last one`() {
        val markers = Markers.none()
        markers.held("t-1", 2, at)
        markers.sending("t-1")
        markers.stored("t-1", 2)
        assertEquals(listOf(SendOutcome("t-1", 1, Reason.MAYBE_SENT, later)), markers.leftAtRestart(later))
        markers.stored("t-1", 1)
        assertTrue(markers.leftAtRestart(later).isEmpty())
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `a stopped draft's marker goes at once`() {
        val markers = Markers.none()
        markers.held("t-1", 2, at)
        assertEquals(false, markers.isEmpty())
        markers.stopped("t-1")
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `several held texts each keep their own marker, in the order they were held`() {
        val markers = Markers.none()
        markers.held("t-1", 1, at)
        markers.held("t-2", 1, at)
        markers.sending("t-2")
        assertEquals(
            listOf(SendOutcome("t-1", 1, Reason.RESTARTED, later), SendOutcome("t-2", 1, Reason.MAYBE_SENT, later)),
            markers.leftAtRestart(later),
        )
    }

    @Test
    fun `a marker for an unknown token changes nothing`() {
        val markers = Markers.none()
        markers.sending("t-9")
        markers.stored("t-9", 1)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `a text to more than 5, or a bad token, is never marked`() {
        val markers = Markers.none()
        assertThrows<IllegalArgumentException> { markers.held("t-1", 0, at) }
        assertThrows<IllegalArgumentException> { markers.held("t-1", 6, at) }
        assertThrows<IllegalArgumentException> { markers.held("", 1, at) }
        assertThrows<IllegalArgumentException> { markers.held("t".repeat(65), 1, at) }
        assertTrue(markers.isEmpty())
        markers.held("t", 5, at)
        markers.held("t".repeat(64), 1, at)
        assertEquals(6, markers.leftAtRestart(later).size)
    }

    @Test
    fun `bytes that are no markers are refused, so a damaged file is never misread`() {
        val markers = Markers.none()
        markers.held("t-1", 2, at)
        val good = markers.toBytes()
        assertThrows<IOException> { Markers.fromBytes(good.copyOf(good.size - 2)) }
        assertThrows<IOException> { Markers.fromBytes(good.copyOf().also { it[3] = 9 }) }
        val stage = String(good, Charsets.ISO_8859_1).replace("HELD", "HOLD").toByteArray(Charsets.ISO_8859_1)
        assertThrows<IOException> { Markers.fromBytes(stage) }
    }

    /** A marker file as bytes, field by field, so a damaged one can be made on purpose. */
    private fun crafted(count: Int, token: String, open: List<Int>, openCount: Int = open.size, stage: String = "HELD"): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(Markers.FORMAT)
        out.writeInt(count)
        out.writeUTF(token)
        out.writeUTF(stage)
        out.writeByte(openCount)
        for (position in open) out.writeByte(position)
        out.writeLong(at.toEpochSecond())
        out.writeInt(0)
        out.writeInt(at.offset.totalSeconds)
        return bytes.toByteArray()
    }

    @Test
    fun `a crafted marker file is read when it is sound, at each edge of what a hold allows`() {
        val read = Markers.fromBytes(crafted(1, "t-1", listOf(1, 5)))
        assertEquals(listOf(1, 5).map { SendOutcome("t-1", it, Reason.RESTARTED, later) }, read.leftAtRestart(later))
        assertEquals(1, Markers.fromBytes(crafted(1, "t", listOf(3))).leftAtRestart(later).size)
        assertEquals(1, Markers.fromBytes(crafted(1, "t".repeat(64), listOf(3))).leftAtRestart(later).size)
        assertEquals(5, Markers.fromBytes(crafted(1, "t-1", listOf(5, 4, 3, 2, 1))).leftAtRestart(later).size)
        assertTrue(Markers.fromBytes(Markers.none().toBytes()).isEmpty(), "no markers at all")
    }

    @Test
    fun `a damaged marker file is refused as unreadable, never left to crash the next start`() {
        val damaged = mapOf(
            "a negative count of open recipients" to crafted(1, "t-1", listOf(), openCount = -1),
            "no open recipient" to crafted(1, "t-1", listOf()),
            "more than 5 open recipients" to crafted(1, "t-1", listOf(1, 2, 3, 4, 5, 1)),
            "an empty token" to crafted(1, "", listOf(1)),
            "a token of 65 characters" to crafted(1, "t".repeat(65), listOf(1)),
            "a position of 0" to crafted(1, "t-1", listOf(0)),
            "a position of 6" to crafted(1, "t-1", listOf(6)),
            "a position given twice" to crafted(1, "t-1", listOf(2, 2)),
            "a negative count of markers" to crafted(-1, "t-1", listOf(1)),
            "a negative count of markers and nothing after" to crafted(-1, "t-1", listOf(1)).copyOf(8),
            "bytes after the last marker" to crafted(1, "t-1", listOf(1)) + byteArrayOf(0),
        )
        for ((what, bytes) in damaged) {
            assertThrows<IOException>(what) { Markers.fromBytes(bytes) }
        }
    }
}
