package org.claudevoice.prototype.audiofocus

import java.time.Instant
import java.util.Locale

/** One event as one line: wall clock, seconds since the run began, run, mode, event, fields. */
object LogLine {
    fun format(
        at: Instant,
        sinceStartMs: Long,
        run: Int,
        mode: Mode,
        event: String,
        fields: Map<String, String> = emptyMap(),
    ): String {
        val since = String.format(Locale.ROOT, "+%d.%03ds", sinceStartMs / 1000, sinceStartMs % 1000)
        val rest = fields.entries.joinToString("") { (k, v) -> " $k=${quote(v)}" }
        return "$at $since run=$run mode=${mode.label} $event$rest"
    }

    private fun quote(value: String): String {
        val flat = value.replace(Regex("\\s+"), " ").trim()
        return if (flat.contains(' ') || flat.isEmpty()) "\"${flat.replace("\"", "'")}\"" else flat
    }
}
