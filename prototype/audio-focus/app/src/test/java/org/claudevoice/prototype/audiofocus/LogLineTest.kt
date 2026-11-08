package org.claudevoice.prototype.audiofocus

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogLineTest {
    @Test
    fun a_line_carries_the_wall_clock_the_time_since_start_the_run_and_the_mode() {
        val line = LogLine.format(
            at = Instant.parse("2026-11-08T10:15:30.123Z"),
            sinceStartMs = 12_345,
            run = 3,
            mode = Mode.SELF_DUCK,
            event = "focus",
            fields = linkedMapOf("change" to "LOSS_TRANSIENT", "volume" to "0.20"),
        )
        assertEquals(
            "2026-11-08T10:15:30.123Z +12.345s run=3 mode=self focus change=LOSS_TRANSIENT volume=0.20",
            line,
        )
    }

    @Test
    fun a_line_stays_one_line_whatever_a_field_holds() {
        val line = LogLine.format(Instant.EPOCH, 0, 1, Mode.SYSTEM_DUCK, "note", mapOf("text" to "a\nb c"))
        assertFalse(line.contains('\n'))
        assertEquals("1970-01-01T00:00:00Z +0.000s run=1 mode=system note text=\"a b c\"", line)
    }
}
