package org.claudevoice.mobile.plugins.sms_send.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * One recipient's text goes as parts, and Android reports each part twice: sent, then perhaps
 * delivered (ADR-CVM-010, "Delivered"). The text is sent only when every part is sent, failed when
 * any part fails, and delivered only when every part has a delivered report. Each outcome is told
 * once, and nothing is told after a failure.
 */
class PartsTest {
    private companion object {
        // Android's own values: Activity.RESULT_OK, SmsManager's RESULT_ERROR_NO_SERVICE, and TP-Status.
        const val OK = -1
        const val NO_SERVICE = 4
        const val DONE = 0x00
        const val TRYING = 0x20
        const val PERMANENT = 0x40
    }

    @Test
    fun `a text is sent only once every part is, and is told so once`() {
        val parts = Parts(3)
        assertEquals(null, parts.sent(0, OK))
        assertEquals(null, parts.sent(2, OK))
        assertEquals(null, parts.sent(2, OK), "the same part twice is still one part")
        assertEquals(Reason.HANDED, parts.sent(1, OK))
        assertEquals(null, parts.sent(1, OK), "told once")
        assertFalse(parts.done, "a delivery report may still come")
    }

    @Test
    fun `one part failing fails the text at once, and nothing is told after`() {
        val parts = Parts(3)
        assertEquals(null, parts.sent(0, OK))
        assertEquals(Reason.NO_SERVICE, parts.sent(1, NO_SERVICE))
        assertTrue(parts.done)
        assertEquals(null, parts.sent(2, OK))
        assertEquals(null, parts.delivered(0, DONE))
    }

    @Test
    fun `a text is delivered only when every part has a delivered report`() {
        val parts = Parts(2)
        parts.sent(0, OK)
        parts.sent(1, OK)
        assertEquals(null, parts.delivered(1, DONE))
        assertEquals(null, parts.delivered(0, TRYING), "still trying is no outcome yet")
        assertEquals(Reason.REPORTED_DELIVERED, parts.delivered(0, DONE))
        assertTrue(parts.done)
        assertEquals(null, parts.delivered(0, DONE), "told once")
    }

    @Test
    fun `a part the network reports failed fails the text, even after it was sent`() {
        val parts = Parts(2)
        parts.sent(0, OK)
        assertEquals(Reason.HANDED, parts.sent(1, OK))
        assertEquals(null, parts.delivered(0, DONE))
        assertEquals(Reason.REPORTED_FAILED, parts.delivered(1, PERMANENT))
        assertTrue(parts.done)
    }

    @Test
    fun `a text delivered before its last sent report is never told as merely sent after`() {
        val parts = Parts(1)
        assertEquals(Reason.REPORTED_DELIVERED, parts.delivered(0, DONE))
        assertEquals(null, parts.sent(0, OK))
    }

    @Test
    fun `a report for a part the text does not have changes nothing`() {
        val parts = Parts(2)
        assertEquals(null, parts.sent(2, OK))
        assertEquals(null, parts.sent(-1, NO_SERVICE))
        assertEquals(null, parts.delivered(5, PERMANENT))
        assertFalse(parts.done)
        parts.sent(0, OK)
        assertEquals(Reason.HANDED, parts.sent(1, OK))
    }

    @Test
    fun `a text of one part is the common case`() {
        val parts = Parts(1)
        assertEquals(Reason.HANDED, parts.sent(0, OK))
        assertEquals(Reason.REPORTED_DELIVERED, parts.delivered(0, DONE))
    }

    @Test
    fun `a text has at least one part`() {
        assertThrows<IllegalArgumentException> { Parts(0) }
    }
}
