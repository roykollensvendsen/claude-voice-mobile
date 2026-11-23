package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The outbox with its journal (ADR-CVM-010): a confirmed text is marked on disk before the undo
 * delay starts and again before Android sees it, and every way it can end before Android's own
 * reports leaves an outcome: stopped by the owner, no default subscription, or refused by Android.
 * A draft never confirmed leaves nothing.
 */
class OutboxJournalTest {
    @TempDir
    lateinit var folder: File

    // Made-up numbers, in the +47 00 range no one is given.
    private val kari = "+4700000001"
    private val ola = "+4700000002"

    private val clock = Clock.fixed(Instant.parse("2026-11-23T19:40:00Z"), ZoneId.of("Europe/Oslo"))

    private class Counted : Chance {
        private var cursors = 0

        override fun installPart() = "p4w8c1z6"

        override fun cursor() = "Qm7Tz2pL" + (++cursors).toString().padStart(4, '0')
    }

    private inner class Phone {
        val timers = mutableListOf<Runnable>()
        val handed = mutableListOf<Handover>()
        val failures = mutableListOf<String>()
        var refuse: String? = null
        var subscribed = true
        var markersOnDiskAtSend: List<SendOutcome> = listOf()
        val journal = journal()
        val outbox = Outbox(
            clock = clock,
            later = { _, task -> timers += task },
            send = {
                markersOnDiskAtSend = markersOnDisk()
                if (it.number == refuse) throw IllegalStateException("radio refused")
                handed += it
            },
            failed = { failures += it },
            tokens = { "t-1" },
            tracking = Tracking(journal, { subscribed }, { failures += it }),
        )

        fun fire() = timers.removeAt(0).run()
    }

    private fun journal() = Journal.open(
        PlainFile(File(folder, "send-outcomes")) {},
        PlainFile(File(folder, "send-markers")) {},
        Counted(),
        clock,
    )

    private val markers get() = File(folder, "send-markers")

    private fun markersOnDisk(): List<SendOutcome> =
        if (markers.exists()) Markers.fromBytes(markers.readBytes()).leftAtRestart(clock.instant().atZone(clock.zone).toOffsetDateTime()) else listOf()

    /** The outcomes a restarted phone would hand the hub, as "token position reason". */
    private fun afterRestart(): List<String> = journal().since(null).getValue("outcomes").jsonArray.map {
        val one = it.jsonObject
        listOf("token", "recipient", "reason").joinToString(" ") { key -> one.getValue(key).jsonPrimitive.content }
    }

    /** A blocking folder where the marker file is written, so the next save of it fails. */
    private fun blockMarkers() = File(folder, "send-markers.new").mkdir()

    private fun unblockMarkers() = File(folder, "send-markers.new").delete()

    private fun Phone.confirmed(vararg to: String): Reply {
        outbox.request(to.toList(), "Kommer om ti")
        return outbox.confirm("t-1", 1)
    }

    @Test
    fun `a draft never confirmed leaves no marker and no outcome, even when stopped`() {
        val phone = Phone()
        phone.outbox.request(kari, "Kommer om ti")
        assertFalse(markers.exists())
        phone.outbox.cancel("t-1")
        assertEquals(listOf<String>(), afterRestart())
    }

    @Test
    fun `the marker is on disk once the owner's yes starts the undo delay`() {
        val phone = Phone()
        phone.confirmed(kari, ola)
        assertEquals(
            listOf("t-1 1 the app restarted while it was held", "t-1 2 the app restarted while it was held"),
            afterRestart(),
        )
    }

    @Test
    fun `a yes whose marker cannot be saved arms nothing, and the draft can be confirmed again`() {
        val phone = Phone()
        blockMarkers()
        val refused = phone.confirmed(kari)
        assertTrue(refused is Reply.Refused)
        assertTrue(phone.timers.isEmpty(), "no undo delay started")
        unblockMarkers()
        assertTrue(phone.outbox.confirm("t-1", 1) is Reply.Answer)
        phone.fire()
        assertEquals(listOf(kari), phone.handed.map { it.number })
    }

    @Test
    fun `a stop during the undo delay stores stopped by the owner for each recipient, and nothing is sent`() {
        val phone = Phone()
        phone.confirmed(kari, ola)
        phone.outbox.cancel("t-1")
        assertFalse(markers.exists(), "the outcome is stored, then the marker goes")
        phone.fire()
        assertEquals(listOf<Handover>(), phone.handed, "the stopped token is never handed to Android")
        assertEquals(listOf("t-1 1 stopped by the owner", "t-1 2 stopped by the owner"), afterRestart())
    }

    @Test
    fun `the marker says sending on disk before Android sees the text, and each recipient is handed its own position`() {
        val phone = Phone()
        phone.confirmed(kari, ola)
        phone.fire()
        assertEquals(listOf(Reason.MAYBE_SENT, Reason.MAYBE_SENT), phone.markersOnDiskAtSend.map { it.reason })
        assertEquals(
            listOf(Handover(kari, "Kommer om ti", "t-1", 1), Handover(ola, "Kommer om ti", "t-1", 2)),
            phone.handed,
        )
    }

    @Test
    fun `a sending marker that cannot be saved never reaches Android`() {
        val phone = Phone()
        phone.confirmed(kari)
        blockMarkers()
        phone.fire()
        assertEquals(listOf<Handover>(), phone.handed)
        assertEquals(1, phone.failures.size)
    }

    @Test
    fun `with no default subscription at the end of the delay, each recipient fails and nothing is sent`() {
        val phone = Phone()
        phone.confirmed(kari, ola)
        phone.subscribed = false
        phone.fire()
        assertEquals(listOf<Handover>(), phone.handed)
        assertFalse(markers.exists())
        assertEquals(listOf("t-1 1 no default subscription", "t-1 2 no default subscription"), afterRestart())
    }

    @Test
    fun `a recipient Android refuses gets its outcome, and the others stay marked for Android's reports`() {
        val phone = Phone()
        phone.refuse = kari
        phone.confirmed(kari, ola)
        phone.fire()
        assertEquals(listOf(ola), phone.handed.map { it.number })
        assertEquals(listOf(2), markersOnDisk().map { it.recipient }, "only the one handed over waits")
        assertEquals(listOf("t-1 1 refused by Android", "t-1 2 it may have been sent; check the text app"), afterRestart())
    }
}
