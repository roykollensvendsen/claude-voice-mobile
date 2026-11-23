package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The outcomes and the crash markers on disk, under one lock (ADR-CVM-010). A marker is on disk
 * before the caller goes on: [held] when the undo delay starts, [sending] just before the text
 * is handed to Android. An outcome is on disk before its recipient leaves the marker, so a crash
 * between the two reports the text twice, never not at all. When the last recipient leaves, the
 * marker file is removed, so a missing marker file means nothing was under way.
 */
class Journal private constructor(
    private val log: OutcomeLog,
    private val markers: Markers,
    private val outcomes: PlainFile,
    private val markerFile: PlainFile,
    private val clock: Clock,
) {
    /** The undo delay of the text with [token], to [recipients] people, starts; throws when that is not on disk. */
    @Synchronized
    @Throws(IOException::class)
    fun held(token: String, recipients: Int) {
        markers.held(token, recipients, now())
        try {
            saveMarkers()
        } catch (failed: IOException) {
            // Not on disk, so not held: the caller arms nothing.
            markers.stopped(token)
            throw failed
        }
    }

    /** The text with [token] is about to be handed to Android; throws when that is not on disk. */
    @Synchronized
    @Throws(IOException::class)
    fun sending(token: String) {
        markers.sending(token)
        saveMarkers()
    }

    /** Keep how [recipient]'s text with [token] ended, then let that recipient leave the marker. */
    @Synchronized
    @Throws(IOException::class)
    fun ended(token: String, recipient: Int, reason: Reason) {
        log.record(SendOutcome(token, recipient, reason, now()), clock.instant())
        saveLog()
        markers.stored(token, recipient)
        saveMarkers()
    }

    /** Keep [reason] for every recipient the marker of [token] still holds open, then drop the marker. */
    @Synchronized
    @Throws(IOException::class)
    fun endedAll(token: String, reason: Reason) {
        for (recipient in markers.open(token)) log.record(SendOutcome(token, recipient, reason, now()), clock.instant())
        saveLog()
        markers.stopped(token)
        saveMarkers()
    }

    /** The answer to `sms_send_outcomes(cursor)`, on disk before it is given; throws when it cannot be. */
    @Synchronized
    @Throws(IOException::class)
    fun since(cursor: String?): JsonObject {
        val answer = log.since(cursor, clock.instant())
        saveLog()
        return answer
    }

    // A change that could not be saved stays marked, so the next call saves it.
    private fun saveLog() {
        if (!log.takeChanged()) return
        try {
            outcomes.write(log.toBytes())
        } catch (failed: IOException) {
            log.markChanged()
            throw failed
        }
    }

    private fun saveMarkers() = if (markers.isEmpty()) markerFile.remove() else markerFile.write(markers.toBytes())

    private fun now() = OffsetDateTime.now(clock)

    companion object {
        /**
         * The journal kept in [outcomes] and [markers]. A damaged outcome file is copied aside and
         * the new log, which counts one dropped, is written over it; a damaged marker file counts
         * one more. Each marker left becomes an outcome. The new log, with its drops, is on disk
         * before the marker file is removed, and a damaged file stays where it was until then, so
         * a crash at any point counts the loss again, never not at all. Throws when a damaged
         * outcome file cannot be copied aside, so it is never overwritten.
         */
        @Throws(IOException::class)
        fun open(outcomes: PlainFile, markers: PlainFile, chance: Chance, clock: Clock): Journal {
            val at = clock.instant()
            val log = readLog(outcomes, chance, at)
            val left = readMarkers(markers)
            if (left == null) log.lost(1)
            left?.leftAtRestart(OffsetDateTime.now(clock))?.forEach { log.record(it, at) }
            val journal = Journal(log, Markers.none(), outcomes, markers, clock)
            journal.saveLog()
            if (left == null) markers.setAside(at)
            markers.remove()
            return journal
        }

        private fun readLog(file: PlainFile, chance: Chance, at: Instant): OutcomeLog =
            try {
                file.read()?.let { OutcomeLog.fromBytes(it, chance) } ?: OutcomeLog.fresh(chance)
            } catch (_: IOException) {
                file.setAside(at)
                OutcomeLog.fresh(chance).also { it.lost(1) }
            }

        // None when the file is damaged; no markers when there is no file.
        private fun readMarkers(file: PlainFile): Markers? =
            try {
                file.read()?.let(Markers::fromBytes) ?: Markers.none()
            } catch (_: IOException) {
                null
            }
    }
}
