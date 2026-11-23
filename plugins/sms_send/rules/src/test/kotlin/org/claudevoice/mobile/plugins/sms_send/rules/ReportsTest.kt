package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Android's sent and delivered reports, as the plugin hears them for each part of each
 * recipient's text (ADR-CVM-010, "Delivered"), turned into that recipient's outcomes.
 */
class ReportsTest {
    @TempDir
    lateinit var folder: File

    private val clock = Clock.fixed(Instant.parse("2026-11-23T19:40:00Z"), ZoneId.of("Europe/Oslo"))

    // Android's own values: Activity.RESULT_OK, and SmsManager's RESULT_ERROR_NO_SERVICE.
    private val ok = -1
    private val noService = 4

    // TP-Status: 0x00 delivered, 0x20 still trying, 0x40 failed.
    private val delivered = 0x00
    private val trying = 0x20
    private val failedStatus = 0x40

    private class Counted : Chance {
        private var cursors = 0

        override fun installPart() = "p4w8c1z6"

        override fun cursor() = "Qm7Tz2pL" + (++cursors).toString().padStart(4, '0')
    }

    private val failures = mutableListOf<String>()

    private fun journal() = Journal.open(
        PlainFile(File(folder, "send-outcomes")) {},
        PlainFile(File(folder, "send-markers")) {},
        Counted(),
        clock,
    )

    private val journal by lazy { journal() }
    private val reports by lazy { Reports(journal) { failures += it } }

    private fun outcomes(): List<String> = journal.since(null).getValue("outcomes").jsonArray.map {
        val one = it.jsonObject
        listOf("token", "recipient", "reason").joinToString(" ") { key -> one.getValue(key).jsonPrimitive.content }
    }

    /** A text marked sending to [recipients] people, as the outbox leaves it just before Android has it. */
    private fun sending(token: String, recipients: Int) {
        journal.held(token, recipients)
        journal.sending(token)
    }

    @Test
    fun `a one-part text is sent on its report, and its recipient leaves the marker`() {
        sending("t-1", 1)
        reports.expect("t-1", 1, 1)
        reports.sent("t-1", 1, 0, ok)
        assertEquals(listOf("t-1 1 handed to the network"), outcomes())
        assertFalse(File(folder, "send-markers").exists())
    }

    @Test
    fun `a long text is sent once every part is, and a failed part fails it once`() {
        sending("t-1", 2)
        reports.expect("t-1", 1, 3)
        reports.expect("t-1", 2, 2)
        reports.sent("t-1", 1, 0, ok)
        reports.sent("t-1", 1, 1, ok)
        reports.sent("t-1", 2, 1, noService)
        reports.sent("t-1", 2, 0, ok)
        assertEquals(listOf("t-1 2 no service"), outcomes())
        reports.sent("t-1", 1, 2, ok)
        assertEquals(listOf("t-1 2 no service", "t-1 1 handed to the network"), outcomes())
    }

    @Test
    fun `delivered is kept once every part has arrived, and still trying keeps nothing`() {
        sending("t-1", 1)
        reports.expect("t-1", 1, 2)
        reports.sent("t-1", 1, 0, ok)
        reports.sent("t-1", 1, 1, ok)
        reports.delivered("t-1", 1, 0, delivered)
        reports.delivered("t-1", 1, 1, trying)
        assertEquals(listOf("t-1 1 handed to the network"), outcomes())
        reports.delivered("t-1", 1, 1, delivered)
        assertEquals(listOf("t-1 1 handed to the network", "t-1 1 the network reported it delivered"), outcomes())
    }

    @Test
    fun `a delivery report the phone cannot read keeps nothing`() {
        sending("t-1", 1)
        reports.expect("t-1", 1, 1)
        reports.sent("t-1", 1, 0, ok)
        reports.delivered("t-1", 1, 0, null)
        assertEquals(listOf("t-1 1 handed to the network"), outcomes())
    }

    @Test
    fun `a failed delivery fails the text, and nothing is kept after`() {
        sending("t-1", 1)
        reports.expect("t-1", 1, 1)
        reports.sent("t-1", 1, 0, ok)
        reports.delivered("t-1", 1, 0, failedStatus)
        reports.delivered("t-1", 1, 0, delivered)
        assertEquals(listOf("t-1 1 handed to the network", "t-1 1 the network reported it failed"), outcomes())
    }

    @Test
    fun `a report for a text the phone no longer follows, such as one from before a restart, changes nothing`() {
        sending("t-1", 1)
        reports.sent("t-1", 1, 0, ok)
        reports.delivered("t-9", 1, 0, delivered)
        assertEquals(listOf<String>(), outcomes())
    }

    @Test
    fun `an outcome that cannot be saved is logged, never thrown, and the marker stays for the next start`() {
        sending("t-1", 1)
        reports.expect("t-1", 1, 1)
        File(folder, "send-outcomes.new").mkdir()
        reports.sent("t-1", 1, 0, ok)
        assertEquals(1, failures.size)
        File(folder, "send-outcomes.new").delete()
        val restarted = journal().since(null).getValue("outcomes").jsonArray.map {
            it.jsonObject.getValue("reason").jsonPrimitive.content
        }
        assertEquals(listOf("it may have been sent; check the text app"), restarted)
    }

    @Test
    fun `only the latest hundred texts are followed, so reports that never come cannot fill the phone`() {
        for (n in 1..101) reports.expect("t-$n", 1, 1)
        sending("t-1", 1)
        sending("t-2", 1)
        reports.sent("t-1", 1, 0, ok)
        reports.sent("t-2", 1, 0, ok)
        assertEquals(listOf("t-2 1 handed to the network"), outcomes())
    }
}
