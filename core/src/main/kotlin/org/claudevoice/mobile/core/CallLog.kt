package org.claudevoice.mobile.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Where the log's lines are kept: the core sees only this, the app gives it the file. */
interface LogStore {
    /** Adds one line at the end, on its own. */
    @Throws(IOException::class)
    fun append(line: String)

    /** Every line, oldest first. */
    @Throws(IOException::class)
    fun lines(): List<String>

    /** Puts [lines] in place of all the lines, at once. */
    @Throws(IOException::class)
    fun replace(lines: List<String>)
}

/**
 * The log kept in one file, one line each. A line is appended on its own; a rewrite writes a new
 * file beside it and renames it over the old, so a crash loses at most the newest line.
 */
class FileLogStore(private val file: File) : LogStore {
    override fun append(line: String) {
        FileOutputStream(file, true).use { it.write("$line\n".toByteArray(Charsets.UTF_8)) }
    }

    override fun lines(): List<String> =
        if (file.exists()) file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() } else emptyList()

    override fun replace(lines: List<String>) {
        val next = File(file.parentFile, "${file.name}.new")
        next.writeText(lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
        if (!next.renameTo(file)) throw IOException("could not put ${next.name} in place of ${file.name}")
    }
}

/**
 * The log of every call to a tool, kept on the phone only, as ADR-CVM-003 sets it: the time in
 * UTC, the tool, the hub, the caller and what came of it. Never a call's inputs or its answer.
 *
 * Lines older than [KEEP], and all but the newest [maxLines], go whenever a line is written,
 * when [prune] is called at the app's start, and when [entries] is read for the screen, so a
 * quiet phone keeps nothing longer either. A text the hub sent, a caller or a tool name, is cut
 * to [TEXT_LIMIT] characters without control characters, and every line is encoded as JSON, so
 * nothing the hub sends can break a line or grow the log.
 */
class CallLog(
    private val store: LogStore,
    private val clock: (() -> Instant)? = null,
    private val maxLines: Int = MAX_LINES,
) {
    /** What came of a call, by the names the record gives them. */
    enum class Outcome(val wire: String) {
        ANSWERED("answered"),
        REFUSED("refused"),
        LATE("late"),
        BROKE("broke"),
        NOT_RUN("not_run"),
        DUPLICATE("duplicate"),
        BUSY("busy"),
    }

    /** Logs one call. [error] is the class of what a broken plugin threw, never its message. */
    @Synchronized
    fun record(tool: String, hub: String?, caller: String?, outcome: Outcome, error: String?) {
        val line = JsonObject(
            mapOf(
                "time" to JsonPrimitive(now().toString()),
                "tool" to JsonPrimitive(cleaned(tool)),
                "hub" to JsonPrimitive(hub),
                "caller" to JsonPrimitive(caller?.let { cleaned(it) }),
                "outcome" to JsonPrimitive(outcome.wire),
                "error" to JsonPrimitive(error),
            ),
        )
        try {
            store.append(line.toString())
            prune()
        } catch (_: IOException) {
            // A log that cannot be written must not stop the call it is about.
        }
    }

    /** Drops what is too old or too many, and rewrites the file only when something went. */
    @Synchronized
    fun prune() {
        val all = store.lines()
        val since = now().minus(KEEP)
        val kept = all.filter { timeOf(it)?.isAfter(since) == true }.takeLast(maxLines)
        if (kept.size != all.size) store.replace(kept)
    }

    /** Every call still kept, oldest first, for the owner's screen. */
    @Synchronized
    fun entries(): List<JsonObject> {
        prune()
        return store.lines().map { Json.parseToJsonElement(it) as JsonObject }
    }

    /** Deletes the whole log, as the owner may at once. */
    @Synchronized
    fun clear() {
        store.replace(emptyList())
    }

    private fun now(): Instant = clock?.invoke() ?: Instant.now()

    companion object {
        /** How long a call is kept: the owner's choice (ADR-CVM-003). */
        val KEEP: Duration = Duration.ofDays(30)

        /** How many calls are kept at most: the owner's choice (ADR-CVM-003). */
        const val MAX_LINES = 5_000

        /** The longest a text from the hub is kept. */
        const val TEXT_LIMIT = 64

        /** The line's time, or null for a line a crash cut short. */
        private fun timeOf(line: String): Instant? = try {
            Instant.parse((Json.parseToJsonElement(line) as JsonObject).getValue("time").jsonPrimitive.content)
        } catch (_: SerializationException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }

        /** Without control characters, at most [TEXT_LIMIT] long, never ending in half a character. */
        private fun cleaned(text: String): String {
            val cut = text.filterNot { it.isISOControl() }.take(TEXT_LIMIT)
            // A plain check, not dropLastWhile: its inlined loop leaves Pitest a boundary no test can reach.
            return if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
        }
    }
}
