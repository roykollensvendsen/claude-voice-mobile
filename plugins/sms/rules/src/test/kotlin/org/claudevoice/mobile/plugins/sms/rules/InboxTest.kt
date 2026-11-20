package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The queue as the receiver and the tool share it: one lock, and kept on disk before an answer
 * leaves, so a cursor handed out survives the app being killed (ADR-CVM-010).
 */
class InboxTest {
    @TempDir
    lateinit var folder: File

    private val at = OffsetDateTime.parse("2026-11-20T08:00:00+01:00")
    private var now: Instant = at.toInstant()

    /** Counts how often the file is written. */
    private class Counting : Sealer {
        var seals = 0
        var broken = false

        override fun seal(plain: ByteArray): ByteArray {
            if (broken) throw IOException("the disk is full")
            seals += 1
            return plain
        }

        override fun open(sealed: ByteArray) = sealed
    }

    private fun file() = File(folder, "sms-queue")

    private fun inbox(seal: Sealer = plainSeal, install: String = "inst0001") =
        Inbox(QueueFile(file(), seal, noFolderSync), FixedChance(install)) { now }

    @Test
    fun `a message added is given by the next answer`() {
        val inbox = inbox()
        inbox.add("+4700000021", "Ses vi?", at, noCountry)
        assertEquals(listOf("Ses vi?"), inbox.since(null).messages.map { it.text })
    }

    @Test
    fun `a message added is on disk at once, so a restart still has it`() {
        inbox().add("+4700000021", "Ses vi?", at, noCountry)
        assertEquals(listOf("inst0001-1"), inbox(install = "inst0002").since(null).messages.map { it.id })
    }

    @Test
    fun `a cursor handed out is on disk before the answer leaves, so a restart still knows it`() {
        val first = inbox()
        first.add("+4700000021", "en", at, noCountry)
        val cursor = first.since(null).cursor
        val restarted = inbox(install = "inst0002")
        val next = restarted.since(cursor)
        assertFalse(next.reset)
        assertEquals(listOf<String>(), next.messages.map { it.id })
    }

    @Test
    fun `an acknowledgement is on disk, so a restart does not give the message again`() {
        val first = inbox()
        first.add("+4700000021", "en", at, noCountry)
        first.since(first.since(null).cursor)
        assertEquals(0, inbox(install = "inst0002").since(null).messages.size)
    }

    @Test
    fun `a poll that changes nothing does not write the file`() {
        val seal = Counting()
        val inbox = inbox(seal)
        inbox.add("+4700000021", "en", at, noCountry)
        val cursor = inbox.since(inbox.since(null).cursor).cursor
        val before = seal.seals
        inbox.since(cursor)
        inbox.since(cursor)
        assertEquals(before, seal.seals)
    }

    @Test
    fun `a poll that drops an old message writes the file`() {
        val seal = Counting()
        val inbox = inbox(seal)
        inbox.add("+4700000021", "en", at, noCountry)
        val before = seal.seals
        now = now.plus(Duration.ofDays(8))
        assertEquals(1, inbox.since(null).dropped)
        assertEquals(before + 1, seal.seals)
        assertEquals(1, inbox(install = "inst0002").since(null).dropped)
    }

    @Test
    fun `when the file cannot be written the answer does not leave`() {
        val seal = Counting()
        val inbox = inbox(seal)
        inbox.add("+4700000021", "en", at, noCountry)
        seal.broken = true
        assertThrows<IOException> { inbox.since(null) }
    }

    @Test
    fun `messages added from many threads at once are all kept`() {
        val inbox = inbox()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(SmsQueue.MOST_PER_ANSWER) { i ->
            pool.execute {
                start.await()
                inbox.add("+4700000021", "melding $i", at, noCountry)
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(SmsQueue.MOST_PER_ANSWER, inbox.since(null).messages.map { it.id }.toSet().size)
    }
}
