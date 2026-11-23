package org.claudevoice.mobile.plugins.sms_send.rules

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.claudevoice.mobile.plugins.sms_send.rules.SendOutcome.Companion.LONGEST_TOKEN
import org.claudevoice.mobile.plugins.sms_send.rules.SendOutcome.Companion.MOST_RECIPIENTS
import java.time.OffsetDateTime

/**
 * The crash markers (ADR-CVM-010, "A held message survives nothing"). When a hold starts the phone
 * marks its token `HELD`; just before it hands the text to Android it marks it `SENDING` and waits
 * until that is on disk. Each recipient leaves the marker once its outcome is stored, and the
 * marker goes with the last one, or at once when the draft is stopped.
 *
 * A marker still there at the next start tells which side of the send the app stopped on: `HELD`
 * becomes "stopped: the app restarted" for each recipient still open, since nothing was handed to
 * Android; `SENDING` becomes `unknown`, since Android may have sent it. Neither is sent again.
 *
 * Its bytes are kept in a plain file, not sealed: a lost key must never drop a marker. They hold
 * only, for each marker, a token, its stage, the open positions and a time.
 */
class Markers private constructor(private val marks: LinkedHashMap<String, Mark>) {
    /** The two sides of a send a marker can be on. */
    enum class Stage { HELD, SENDING }

    private class Mark(var stage: Stage, val open: MutableList<Int>, val at: OffsetDateTime)

    /** A hold of the text with [token], to [recipients] people, starts at [at]. */
    fun held(token: String, recipients: Int, at: OffsetDateTime) {
        require(token.length in 1..SendOutcome.LONGEST_TOKEN) { "a token is 1 to $LONGEST_TOKEN characters" }
        require(recipients in 1..SendOutcome.MOST_RECIPIENTS) { "a text goes to 1 to $MOST_RECIPIENTS people" }
        marks[token] = Mark(Stage.HELD, (1..recipients).toMutableList(), at)
    }

    /** The text with [token] is about to be handed to Android. */
    fun sending(token: String) {
        marks[token]?.stage = Stage.SENDING
    }

    /** The outcome of the [recipient] at that position is stored. */
    fun stored(token: String, recipient: Int) {
        val mark = marks[token] ?: return
        mark.open.remove(recipient)
        if (mark.open.isEmpty()) marks.remove(token)
    }

    /** The draft with [token] was stopped, so nothing of it waits. */
    fun stopped(token: String) {
        marks.remove(token)
    }

    /** Whether no marker is left. */
    fun isEmpty(): Boolean = marks.isEmpty()

    /** What each marker left at a start means, at [at], one outcome for each open recipient. */
    fun leftAtRestart(at: OffsetDateTime): List<SendOutcome> = marks.flatMap { (token, mark) ->
        val reason = if (mark.stage == Stage.HELD) Reason.RESTARTED else Reason.MAYBE_SENT
        mark.open.map { SendOutcome(token, it, reason, at) }
    }

    /** The markers as bytes, for their plain file; [fromBytes] reads them back. */
    fun toBytes(): ByteArray {
        // In memory, unbuffered: nothing to flush or close.
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(FORMAT)
        out.writeInt(marks.size)
        for ((token, mark) in marks) {
            out.writeUTF(token)
            out.writeUTF(mark.stage.name)
            out.writeByte(mark.open.size)
            for (position in mark.open) out.writeByte(position)
            OutcomeLog.writeTime(out, mark.at)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val FORMAT = 1

        /** No markers: a new install, or every send settled. */
        fun none() = Markers(LinkedHashMap())

        /**
         * Markers read back from [toBytes]; an [IOException] when the bytes are not markers,
         * whatever is wrong with them, so a damaged file is refused here and never crashes a
         * later start. Each marker is held to what [held] allows, and nothing may follow the last.
         */
        @Throws(IOException::class)
        fun fromBytes(bytes: ByteArray): Markers {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            sound(input.readInt() == FORMAT, "not markers of this format")
            val count = input.readInt()
            sound(count >= 0, "a negative count of markers")
            val marks = LinkedHashMap<String, Mark>()
            repeat(count) {
                val token = input.readUTF()
                marks[token] = mark(token, input)
            }
            sound(input.read() == -1, "bytes after the last marker")
            return Markers(marks)
        }

        private fun mark(token: String, input: DataInputStream): Mark {
            val stage = stage(input.readUTF())
            val count = input.readByte().toInt()
            sound(token.length in 1..LONGEST_TOKEN && count >= 1, "a marker no hold could have left")
            // Distinct positions from 1 to 5, so never more than 5 of them.
            val open = MutableList(count) { input.readByte().toInt() }
            sound(POSITIONS.containsAll(open) && open.toSet().size == count, "positions no hold could have left")
            return Mark(stage, open, OutcomeLog.readTime(input))
        }

        private val POSITIONS = (1..MOST_RECIPIENTS).toSet()

        /** Bytes that break what a hold could have written are no markers. */
        private fun sound(holds: Boolean, what: String) {
            if (!holds) throw IOException(what)
        }

        private fun stage(name: String): Stage =
            Stage.entries.firstOrNull { it.name == name } ?: throw IOException("no stage $name")
    }
}
