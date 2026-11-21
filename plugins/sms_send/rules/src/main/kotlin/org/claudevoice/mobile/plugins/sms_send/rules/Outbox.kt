package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a call comes to: an answer, or a refusal in words. */
sealed interface Reply {
    data class Answer(val content: JsonObject) : Reply

    data class Refused(val say: String, val code: String? = null) : Reply
}

/** Runs a task once, after a wait. */
fun interface Later {
    fun run(wait: Duration, task: Runnable)
}

/**
 * The texts waiting to go, held in memory only (messages-v1 §8, choice B). A request keeps a
 * draft and sends nothing; a confirm of its latest version arms a timer of [delay]; the timer
 * hands the stored text to the phone unless the owner said stop first. Stop and the timer take
 * one lock, so exactly one of them wins. A held text dies with the app and never goes later.
 *
 * [clock] gives the time and the zone the too-late answer names. [later] runs a task after a
 * wait. [send] hands a text to the phone's default SIM and throws when the radio refuses, and
 * [failed] logs that on the phone, since the hub hears no outcome yet.
 */
class Outbox(
    private val clock: Clock,
    private val later: Later,
    private val send: (String, String) -> Unit,
    private val failed: (String) -> Unit,
    private val tokens: () -> String,
    private val delay: Duration = DEFAULT_DELAY,
) {
    init {
        require(delay >= FLOOR) { "the undo delay may not be under ${FLOOR.seconds} seconds" }
    }

    private sealed interface State {
        data object Held : State
        data object Armed : State
        data class Handed(val at: Instant) : State
        data object Stopped : State
    }

    private class Draft(val token: String, val to: String, var text: String, var version: Int, var state: State) {
        /** When the owner's yes armed it; it counts toward the limits until it is stopped. */
        var confirmed: Instant? = null
    }

    private val drafts = mutableMapOf<String, Draft>()

    /**
     * Keeps [text] for [to] and sends nothing. A new text for a held draft is its next version.
     * [resolve] turns `to` into a number, or the answer the owner hears instead; it is asked only
     * once the text has passed, so a refused text costs no name lookup.
     */
    @Synchronized
    fun request(to: String, text: String, resolve: (String) -> Recipients.Resolved = Recipients::number): Reply {
        val wrong = Limits.text(text)
        return when {
            text.isEmpty() -> Reply.Refused(EMPTY)
            wrong != null -> Reply.Refused(wrong, Limits.TEXT_REFUSED)
            else -> when (val resolved = resolve(to)) {
                is Recipients.Resolved.Answered -> resolved.reply
                is Recipients.Resolved.To -> numbered(resolved, text)
            }
        }
    }

    private fun numbered(to: Recipients.Resolved.To, text: String): Reply {
        val blocked = Limits.blocked(counted(), to.number, clock.instant())
        return when {
            !E164.matches(to.number) -> Reply.Refused(NOT_A_NUMBER, INVALID_RECIPIENT)
            blocked != null -> Reply.Refused(blocked.say("Not drafted", clock.zone), Limits.RATE_LIMITED)
            else -> drafted(to.number, to.name?.let { "$it, ${to.number}" } ?: to.number, text)
        }
    }

    private fun drafted(to: String, named: String, text: String): Reply {
        val held = drafts.values.firstOrNull { it.to == to && it.state == State.Held }
        val draft = held?.apply {
            this.text = text
            version += 1
        } ?: Draft(tokens(), to, text, 1, State.Held).also { drafts[it.token] = it }
        return answer(
            "token" to JsonPrimitive(draft.token),
            "version" to JsonPrimitive(draft.version),
            "say" to JsonPrimitive("Draft to $named: '$text'"),
        )
    }

    /** Arms the timer for the stored text of [version], which must be the latest one. */
    @Synchronized
    fun confirm(token: String, version: Int): Reply {
        val draft = drafts[token]?.takeIf { it.state == State.Held }
        val blocked = draft?.let { Limits.blocked(counted(), it.to, clock.instant()) }
        return when {
            draft == null -> Reply.Refused(NO_SUCH, NO_DRAFT)
            version != draft.version -> Reply.Refused(CHANGED, STALE_VERSION)
            blocked != null -> Reply.Refused(blocked.say("Not sent", clock.zone), Limits.RATE_LIMITED)
            else -> armed(draft)
        }
    }

    private fun armed(draft: Draft): Reply {
        draft.state = State.Armed
        draft.confirmed = clock.instant()
        later.run(delay) { fire(draft) }
        return answer("say" to JsonPrimitive("Sending to ${draft.to} in ${delay.seconds} seconds. $STOP_HINT"))
    }

    /** Stops an armed or held draft; after the timer, says when it was handed to the phone. */
    @Synchronized
    fun cancel(token: String): Reply {
        val draft = drafts[token] ?: return Reply.Refused(NO_SUCH, NO_DRAFT)
        return when (val state = draft.state) {
            State.Held, State.Armed -> {
                draft.state = State.Stopped
                answer("say" to JsonPrimitive(STOPPED))
            }
            is State.Handed -> answer("say" to JsonPrimitive(tooLate(state.at)))
            State.Stopped -> Reply.Refused(NO_SUCH, NO_DRAFT)
        }
    }

    /** The sends that count toward the limits: armed or handed to the phone, never stopped. */
    private fun counted(): List<Counted> = drafts.values.mapNotNull { draft ->
        draft.confirmed?.takeIf { draft.state != State.Stopped }?.let { Counted(draft.to, it) }
    }

    private fun fire(draft: Draft) {
        synchronized(this) {
            if (draft.state != State.Armed) return
            draft.state = State.Handed(clock.instant())
        }
        // Outside the lock: the state has decided, and the radio may be slow.
        @Suppress("TooGenericExceptionCaught") // whatever the radio throws, it is logged, never thrown on
        try {
            send(draft.to, draft.text)
        } catch (_: RuntimeException) {
            failed(SEND_FAILED)
        }
    }

    private fun tooLate(at: Instant) =
        "Too late to stop; it was handed to the phone at ${TIME.format(at.atZone(clock.zone))}. $NOT_CONFIRMED"

    private fun answer(vararg pairs: Pair<String, JsonPrimitive>) = Reply.Answer(JsonObject(mapOf(*pairs)))

    companion object {
        /**
         * The outbox the phone uses: the phone's clock and zone, a timer thread of its own, and
         * tokens no one can guess. [send] and [failed] are the phone's radio and log.
         */
        fun onThePhone(send: (String, String) -> Unit, failed: (String) -> Unit): Outbox {
            val timer = Executors.newSingleThreadScheduledExecutor()
            return Outbox(
                clock = Clock.systemDefaultZone(),
                later = { wait, task -> timer.schedule(task, wait.toMillis(), TimeUnit.MILLISECONDS) },
                send = send,
                failed = failed,
                tokens = { UUID.randomUUID().toString() },
            )
        }

        val DEFAULT_DELAY: Duration = Duration.ofSeconds(10)
        val FLOOR: Duration = Duration.ofSeconds(5)
        const val STALE_VERSION = "stale_version"
        const val NO_DRAFT = "no_draft"
        const val INVALID_RECIPIENT = "invalid_recipient"
        const val SEND_FAILED = "send_failed"

        private val E164 = Regex("\\+[1-9][0-9]{6,14}")
        private val TIME = DateTimeFormatter.ofPattern("HH.mm")
        private const val NOT_CONFIRMED = "I can't yet confirm delivery."
        private const val STOP_HINT = "Say stop to cancel. $NOT_CONFIRMED"
        private const val STOPPED = "Stopped, not sent."
        private const val CHANGED = "It changed; read it back first."
        private const val NO_SUCH = "There is no draft by that token waiting; make a new request."
        private const val NOT_A_NUMBER = "A text goes to one number in full international form, such as +4700000001."
        private const val EMPTY = "The text is empty; say what it should say."
    }
}
