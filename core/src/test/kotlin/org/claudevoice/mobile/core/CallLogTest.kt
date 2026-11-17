package org.claudevoice.mobile.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A store in memory that counts how often the whole log is rewritten. */
class MemoryStore : LogStore {
    val lines = mutableListOf<String>()
    var rewrites = 0
    var failing = false

    override fun append(line: String) {
        if (failing) throw IOException("the disk is full")
        lines.add(line)
    }

    override fun lines(): List<String> = lines.toList()

    override fun replace(lines: List<String>) {
        rewrites++
        this.lines.clear()
        this.lines.addAll(lines)
    }
}

class CallLogTest {
    private val store = MemoryStore()
    private var now = Instant.parse("2026-11-17T12:00:00Z")
    private val log = CallLog(store, clock = { now })

    private fun record(tool: String = "status_battery", caller: String? = "voice", outcome: CallLog.Outcome = CallLog.Outcome.ANSWERED) =
        log.record(tool, "laptop-a", caller, outcome, null)

    @Test
    fun `a line holds the time in UTC, the tool, the hub, the caller and what came of it, and nothing else`() {
        log.record("status_battery", "laptop-a", "laptop-a/billing", CallLog.Outcome.BROKE, "java.lang.IllegalStateException")
        val line = Json.parseToJsonElement(store.lines.single()).jsonObject
        assertEquals(listOf("time", "tool", "hub", "caller", "outcome", "error"), line.keys.toList())
        assertEquals("2026-11-17T12:00:00Z", line["time"]!!.jsonPrimitive.content)
        assertEquals("status_battery", line["tool"]!!.jsonPrimitive.content)
        assertEquals("laptop-a", line["hub"]!!.jsonPrimitive.content)
        assertEquals("laptop-a/billing", line["caller"]!!.jsonPrimitive.content)
        assertEquals("broke", line["outcome"]!!.jsonPrimitive.content)
        assertEquals("java.lang.IllegalStateException", line["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no caller and no hub, as from a version 0 hub, are written as null`() {
        log.record("status_battery", null, null, CallLog.Outcome.ANSWERED, null)
        val line = Json.parseToJsonElement(store.lines.single()).jsonObject
        assertEquals(JsonNull, line["hub"])
        assertEquals(JsonNull, line["caller"])
        assertEquals(JsonNull, line["error"])
    }

    @Test
    fun `the seven outcomes have the names the record gives them`() {
        assertEquals(
            listOf("answered", "refused", "late", "broke", "not_run", "duplicate", "busy"),
            CallLog.Outcome.entries.map { it.wire },
        )
    }

    @Test
    fun `a caller is cut to 64 characters and loses control characters, and cannot break a line`() {
        record(caller = "a\"b\nc\u0000d\u001b" + "x".repeat(100))
        val caller = Json.parseToJsonElement(store.lines.single()).jsonObject["caller"]!!.jsonPrimitive.content
        assertEquals("a\"bcd" + "x".repeat(59), caller)
        assertEquals(1, store.lines.size)
    }

    @Test
    fun `a cut never leaves half of a character that takes two`() {
        record(caller = "x".repeat(63) + "😀")
        assertEquals("x".repeat(63), log.entries().single()["caller"]!!.jsonPrimitive.content)
        record(caller = "x".repeat(62) + "😀")
        assertEquals("x".repeat(62) + "😀", log.entries().last()["caller"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool name the hub sent is cleaned the same way`() {
        record(tool = "status_\n" + "y".repeat(100))
        assertEquals("status_" + "y".repeat(57), log.entries().single()["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a line older than 30 days goes when a line is written`() {
        record(tool = "status_old")
        now = now.plus(Duration.ofDays(30)).plusSeconds(1)
        record(tool = "status_new")
        // Read straight from the store: entries() prunes too, and would hide a write that did not.
        assertEquals(1, store.lines.size)
        assertTrue("status_new" in store.lines.single())
    }

    @Test
    fun `a line just 30 days old stays`() {
        record(tool = "status_old")
        now = now.plus(Duration.ofDays(30)).minusSeconds(1)
        assertEquals(1, log.entries().size)
    }

    @Test
    fun `on a quiet phone old lines go at start-up and when the log is read, with no new call`() {
        record()
        now = now.plus(Duration.ofDays(31))
        log.prune()
        assertTrue(store.lines.isEmpty(), "pruned at start-up")
        record()
        now = now.plus(Duration.ofDays(31))
        assertTrue(log.entries().isEmpty(), "pruned when the screen reads it")
        assertTrue(store.lines.isEmpty())
    }

    @Test
    fun `at most the newest lines are kept, the oldest go first`() {
        val small = CallLog(store, clock = { now }, maxLines = 3)
        for (i in 1..5) small.record("status_t$i", null, null, CallLog.Outcome.ANSWERED, null)
        assertEquals(listOf("status_t3", "status_t4", "status_t5"), small.entries().map { it["tool"]!!.jsonPrimitive.content })
    }

    @Test
    fun `the log keeps 30 days and 5 000 lines, as the owner chose`() {
        assertEquals(Duration.ofDays(30), CallLog.KEEP)
        assertEquals(5_000, CallLog.MAX_LINES)
    }

    @Test
    fun `nothing is rewritten when nothing is old`() {
        record()
        record()
        log.prune()
        log.entries()
        assertEquals(0, store.rewrites)
    }

    @Test
    fun `a line cut short by a crash is dropped, the rest kept`() {
        record(tool = "status_a")
        store.lines.add("""{"time":"2026-11-17T12:00:00Z","to""")
        record(tool = "status_b")
        assertEquals(listOf("status_a", "status_b"), log.entries().map { it["tool"]!!.jsonPrimitive.content })
        assertEquals(2, store.lines.size)
    }

    @Test
    fun `the owner can delete all of it at once`() {
        record()
        record()
        log.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun `a log that cannot be written never stops the call`() {
        store.failing = true
        record()
        assertTrue(store.lines.isEmpty())
    }

    @Test
    fun `the default clock is the wall clock`() {
        val plain = CallLog(store)
        val before = Instant.now()
        plain.record("status_battery", null, null, CallLog.Outcome.ANSWERED, null)
        val time = Instant.parse(plain.entries().single()["time"]!!.jsonPrimitive.content)
        assertFalse(time.isBefore(before.minusSeconds(1)) || time.isAfter(Instant.now().plusSeconds(1)), time.toString())
    }
}

class FileLogStoreTest {
    private val dir: File = Files.createTempDirectory("calls").toFile()
    private val file = File(dir, "calls.jsonl")
    private val store = FileLogStore(file)

    @Test
    fun `no file yet is an empty log`() {
        assertEquals(emptyList<String>(), store.lines())
    }

    @Test
    fun `lines are appended one by one and read back in order`() {
        store.append("""{"a":1}""")
        store.append("""{"b":2}""")
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), store.lines())
        assertEquals("{\"a\":1}\n{\"b\":2}\n", file.readText())
    }

    @Test
    fun `a rewrite goes through a new file renamed over the old, and leaves no new file behind`() {
        store.append("""{"a":1}""")
        store.replace(listOf("""{"c":3}"""))
        assertEquals(listOf("""{"c":3}"""), store.lines())
        assertEquals(listOf("calls.jsonl"), dir.list()!!.toList())
        store.append("""{"d":4}""")
        assertEquals(listOf("""{"c":3}""", """{"d":4}"""), store.lines())
    }

    @Test
    fun `an empty line, as a crash may leave, is not read as a line`() {
        file.writeText("{\"a\":1}\n\n")
        assertEquals(listOf("""{"a":1}"""), store.lines())
    }

    @Test
    fun `a rewrite that cannot rename says so`() {
        val blocked = File(dir, "calls.jsonl.new").apply { mkdir(); File(this, "x").writeText("") }
        assertTrue(blocked.isDirectory)
        org.junit.jupiter.api.assertThrows<IOException> { store.replace(listOf("{}")) }
    }
}
