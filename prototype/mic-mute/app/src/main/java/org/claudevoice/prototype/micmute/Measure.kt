package org.claudevoice.prototype.micmute

import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** One event as one line: wall clock, time since the run began, the measurement, event, fields. */
object LogLine {
    fun format(at: Instant, sinceStartMs: Long, measure: String, event: String, fields: Map<String, String> = emptyMap()): String {
        val since = String.format(Locale.ROOT, "+%d.%03ds", sinceStartMs / 1000, sinceStartMs % 1000)
        val rest = fields.entries.joinToString("") { (k, v) -> " $k=${quote(v)}" }
        return "$at $since $measure $event$rest"
    }

    private fun quote(value: String): String {
        val flat = value.replace(Regex("\\s+"), " ").trim()
        return if (flat.contains(' ') || flat.isEmpty()) "\"${flat.replace("\"", "'")}\"" else flat
    }
}

/** M1a and M4: whether the stand-in recording heard anything, and where silence began. */
object Silence {
    fun nonZero(samples: ShortArray): Int = samples.count { it.toInt() != 0 }

    /**
     * The index where a run of at least [minRun] zero samples starts, or null. A steady tone
     * crosses zero every cycle, so one zero sample is not silence; a run cut off by the end of
     * the buffer is not counted yet either.
     */
    fun firstSilentRun(samples: ShortArray, minRun: Int): Int? {
        var start = -1
        for (i in samples.indices) {
            if (samples[i].toInt() == 0) {
                if (start < 0) start = i
                if (i - start + 1 >= minRun) return start
            } else {
                start = -1
            }
        }
        return null
    }
}

/**
 * M4 across buffers: counts every sample fed since the last [reset], and reports once the index
 * where the first run of [minRun] zeros began, even when the run spans buffers.
 */
class SilenceClock(private val minRun: Int) {
    /** Samples fed since the last [reset]. */
    var seen = 0L
        private set
    private var runStart = -1L
    private var found = false

    fun feed(samples: ShortArray): Long? {
        var result: Long? = null
        for (sample in samples) {
            if (!found) {
                if (sample.toInt() == 0) {
                    if (runStart < 0) runStart = seen
                    if (seen - runStart + 1 >= minRun) {
                        found = true
                        result = runStart
                    }
                } else {
                    runStart = -1
                }
            }
            seen++
        }
        return result
    }

    fun reset() {
        seen = 0
        runStart = -1
        found = false
    }
}

/** ADR-CVM-008: a toggle within [windowMs] of the last one is ignored, so one tap never counts twice. */
class Debounce(private val windowMs: Long) {
    private var last: Long? = null

    fun accept(nowMs: Long): Boolean {
        val previous = last
        if (previous != null && nowMs - previous < windowMs) return false
        last = nowMs
        return true
    }
}

/** M4's target is a percentile of the delays, by nearest rank. */
object Percentile {
    fun of(values: List<Long>, percent: Int): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = (percent * sorted.size + 99) / 100
        return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)]
    }
}

/** M5: any unmute seen while the test app holds the mute is a leak, however short. */
class MuteWatch {
    var leaks = 0
        private set
    private var expectMuted = false

    fun expect(muted: Boolean) {
        expectMuted = muted
    }

    /** True when this observation is a leak. */
    fun observe(muted: Boolean): Boolean {
        val leak = expectMuted && !muted
        if (leak) leaks++
        return leak
    }
}

/**
 * M1c: which word slots are muted, written down before the run and never said aloud. Slot `i`
 * runs from `i * slotMs` after the start; the laptop says word `i` in the middle of it.
 */
class Schedule(val muted: List<Boolean>, val slotMs: Long = 10_000) {
    val pattern: String = muted.joinToString("") { if (it) "M" else "-" }

    fun mutedAt(elapsedMs: Long): Boolean = muted.getOrNull((elapsedMs / slotMs).toInt()) ?: false

    companion object {
        /** A third to two thirds muted, so the run can fail either way. */
        fun random(slots: Int, random: Random): Schedule {
            val count = random.nextInt(slots / 3, slots * 2 / 3 + 1)
            val chosen = (0 until slots).shuffled(random).take(count).toSet()
            return Schedule(List(slots) { it in chosen })
        }

        /** The start of the next whole minute, a time the laptop's script can wait for too. */
        fun nextMinute(nowMs: Long): Long = (nowMs / 60_000 + 1) * 60_000
    }
}

/**
 * M1b: one reading of Android's recording configurations while muted. The Claude app's capture is
 * found by elimination, so exactly one capture must be active; anything else fails, unguessed.
 */
object Reading {
    fun judge(silenced: List<Boolean>): String = when {
        silenced.size != 1 -> "fail: ${silenced.size} captures"
        !silenced.single() -> "fail: not silenced"
        else -> "pass"
    }
}

/** ADR-CVM-008's toggle sounds: a short falling tone to mute, a rising one to unmute. */
object Tones {
    private const val SECONDS = 0.25
    private const val HIGH = 880.0
    private const val LOW = 440.0

    fun falling(sampleRate: Int): ShortArray = sweep(sampleRate, HIGH, LOW)

    fun rising(sampleRate: Int): ShortArray = sweep(sampleRate, LOW, HIGH)

    fun zeroCrossings(samples: ShortArray, from: Int, to: Int): Int =
        (from + 1 until to).count { (samples[it - 1] < 0) != (samples[it] < 0) }

    private fun sweep(sampleRate: Int, from: Double, to: Double): ShortArray {
        val n = (sampleRate * SECONDS).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val f = from + (to - from) * i / n
            phase += 2 * PI * f / sampleRate
            (sin(phase) * 8_000).toInt().toShort()
        }
    }
}
