package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * A queue the phone saved before the shared cursor queue (ADR-CVM-007) is read the same after
 * it: the bytes of one fixed queue are pinned, taken from the code before the move. Every sender
 * and text here is made up.
 */
class QueueBytesTest {
    private class Fixed : Chance {
        private var cursors = 0

        override fun installPart() = "inst0001"

        override fun cursor(): String {
            cursors += 1
            return "C" + cursors.toString().padStart(11, '0')
        }
    }

    private fun queue(): SmsQueue {
        val now = OffsetDateTime.parse("2026-11-20T08:00:00+01:00")
        val q = SmsQueue.afterLostKey(2, Fixed())
        q.add("+4700000021", "Hei", now, now.toInstant(), noCountry)
        q.add("Eksempelbank", "Koden er 482913", now.plusMinutes(1), now.toInstant(), noCountry)
        val first = q.since(null, now.toInstant())
        q.add("+4700000022", "Ses i morgen", now.plusMinutes(2), now.toInstant(), noCountry)
        q.since(first.cursor, now.toInstant())
        return q
    }

    @Test
    fun `a queue is saved as the same bytes as before the shared cursor queue`() {
        assertEquals(BEFORE, queue().toBytes().joinToString("") { "%02x".format(it) })
    }

    private companion object {
        // Taken from SmsQueue.toBytes() on main at 968d234, before the move.
        const val BEFORE =
            "000000020008696e7374303030310000000000000004000000020000000200000002000c433030303030303030303031" +
            "000000000000000200000002000c43303030303030303030303200000000000000030000000200000001000000000000" +
            "00030000000b2b343730303030303032320000000c5365732069206d6f7267656e000000000000006afff06800000000" +
            "00000e10"
    }
}
