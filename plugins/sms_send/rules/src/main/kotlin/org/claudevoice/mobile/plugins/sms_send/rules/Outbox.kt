package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
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

/**
 * One recipient's text, handed to Android: [number] and [text], and the [token] of the draft
 * with the recipient's [position] in it, counted from 1, so Android's reports find their outcome.
 */
data class Handover(val number: String, val text: String, val token: String, val position: Int)

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
 * [failed] logs that on the phone.
 *
 * With [tracking] (ADR-CVM-010), a confirmed text is marked on disk before its undo delay starts
 * and again before Android sees it, and each way it ends before Android's own reports is stored as
 * its outcome: stopped by the owner, no default subscription, or refused by Android. A draft never
 * confirmed leaves nothing.
 */
// Each is a separate stand-in the tests replace; grouping them would only hide which one a test sets.
@Suppress("LongParameterList")
class Outbox(
    private val clock: Clock,
    private val later: Later,
    private val send: (Handover) -> Unit,
    private val failed: (String) -> Unit,
    private val tokens: () -> String,
    private val delay: Duration = DEFAULT_DELAY,
    private val tracking: Tracking? = null,
) {
    init {
        require(delay >= FLOOR) { "the undo delay may not be under ${FLOOR.seconds} seconds" }
    }

    private sealed interface State {
        data object Held : State
        data object Armed : State
        data class Handed(val at: Instant) : State

        /** Failed before Android had it, as [say] tells; it still counts, as any failed text does. */
        data class Unsent(val say: String) : State
        data object Stopped : State
    }

    private class Draft(
        val token: String,
        var to: List<String>,
        @JvmField var text: String, // read only inside the draft, so it needs no getter
        var version: Int,
        var state: State,
    ) {
        /** How the read-back named each recipient: the number, or the contact's name and the number. */
        @JvmField // read only inside the draft, so it needs no getter
        var named: List<String> = to

        val names get() = named.joinToString("; ")

        /** When the owner's yes armed it; each recipient counts toward the limits until it is stopped. */
        var confirmed: Instant? = null

        /** How many of [to] Android took, and how the read-back named each one it refused. */
        var handed = 0
        val refused = mutableListOf<String>()

        /** Android refused, or would refuse, the recipient at [index]: logged, and its outcome kept. */
        fun refuse(index: Int, failed: (String) -> Unit, tracking: Tracking?) {
            refused += named[index]
            failed(SEND_FAILED)
            tracking?.refused(token, index + 1)
        }

        /** The owner stopped it; only a confirmed text has a marker, and so an outcome. */
        fun stop(tracking: Tracking?) {
            if (state == State.Armed) tracking?.stopped(token)
            state = State.Stopped
        }

        /** Hand each recipient's text to [send]; one Android refuses never stops the next. */
        fun handOver(send: (Handover) -> Unit, failed: (String) -> Unit, tracking: Tracking?) {
            for ((index, number) in to.withIndex()) {
                @Suppress("TooGenericExceptionCaught") // whatever the radio throws, it is logged, never thrown on
                try {
                    send(Handover(number, text, token, index + 1))
                    handed += 1
                } catch (_: SecurityException) {
                    // Sending was taken back: Android sends nothing more, so this one and every one after are refused.
                    for (each in index until to.size) refuse(each, failed, tracking)
                    break
                } catch (_: RuntimeException) {
                    refuse(index, failed, tracking)
                }
            }
        }
    }

    private val drafts = mutableMapOf<String, Draft>()

    /**
     * Keeps [text] for [to] and sends nothing. A new text for a held draft is its next version.
     * [recipients] turns `to` into a number, or the answer the owner hears instead; without it
     * `to` is a number. It is asked only once the text has passed, so a refused text costs no
     * name lookup, but a refused text still closes the open choices, as any request does.
     */
    @Synchronized
    fun request(to: String, text: String, recipients: Recipients? = null): Reply = request(listOf(to), text, recipients)

    /**
     * One text to everyone [to] names, read back and confirmed once (ADR-CVM-010, "Several
     * recipients"). The same number twice is one recipient, in its first place.
     */
    @Synchronized
    fun request(to: List<String>, text: String, recipients: Recipients? = null): Reply {
        val refused = refusal(text)
        // A refused text still closes the open choices, so no later pick lands on them.
        if (refused != null) recipients?.close()
        return refused ?: when (val resolved = recipients?.resolve(to) ?: Recipients.numbers(to)) {
            is Recipients.Group.Answered -> resolved.reply
            is Recipients.Group.To -> numbered(resolved.all.distinctBy { it.number }, text, recipients)
        }
    }

    private fun numbered(to: List<Recipients.Resolved.To>, text: String, recipients: Recipients?): Reply {
        val numbers = to.map { it.number }
        val wrong = numbers.filterNot(E164::matches)
        val blocked = Limits.group(counted(), numbers, clock.instant()).refused("Not drafted", clock.zone)
        return when {
            wrong.isNotEmpty() -> Reply.Refused(NOT_A_NUMBER, INVALID_RECIPIENT)
            blocked != null -> blocked
            else -> drafted(numbers, to.map(::named), to.map { readBack(it, recipients) }, text)
        }
    }

    // The oldest held draft that shares a recipient takes the new list and text as its next
    // version. Every other held draft sharing one is stopped, so one recipient is in at most one
    // held draft and two yeses never send one person two texts (ADR-CVM-010, "Several recipients").
    // [named] is kept for later answers; [said] adds the marks, read back now and never again,
    // since a mark such as "never texted before" goes stale once the text is sent.
    private fun drafted(to: List<String>, named: List<String>, said: List<String>, text: String): Reply {
        val sharing = drafts.values.filter { it.state == State.Held && it.to.intersect(to.toSet()).isNotEmpty() }
        sharing.drop(1).forEach { it.state = State.Stopped }
        val draft = sharing.firstOrNull()?.apply {
            this.to = to
            this.text = text
            version += 1
        } ?: Draft(tokens(), to, text, 1, State.Held).also { drafts[it.token] = it }
        draft.named = named
        return answer(
            "token" to JsonPrimitive(draft.token),
            "version" to JsonPrimitive(draft.version),
            "say" to JsonPrimitive("Draft to ${said.joinToString("; ")}: '$text'${replaced(sharing.size - 1)}"),
        )
    }

    /** Arms the timer for the stored text of [version], which must be the latest one. */
    @Synchronized
    fun confirm(token: String, version: Int): Reply {
        val draft = drafts[token]?.takeIf { it.state == State.Held }
        val blocked = draft?.let { Limits.group(counted(), it.to, clock.instant()).refused("Not sent", clock.zone) }
        return when {
            draft == null -> Reply.Refused(NO_SUCH, NO_DRAFT)
            version != draft.version -> Reply.Refused(CHANGED, STALE_VERSION)
            blocked != null -> blocked
            else -> armed(draft)
        }
    }

    private fun armed(draft: Draft): Reply {
        val refusal = when {
            tracking?.subscribed() == false -> NO_DEFAULT_SIM
            tracking?.held(draft.token, draft.to.size) == false -> NOT_HELD
            else -> null
        }
        if (refusal != null) return Reply.Refused(refusal)
        draft.state = State.Armed
        draft.confirmed = clock.instant()
        later.run(delay) { fire(draft) }
        return answer("say" to JsonPrimitive("Sending to ${draft.names} in ${delay.seconds} seconds. $STOP_HINT"))
    }

    /** Stops an armed or held draft; after the timer, says when it was handed to the phone. */
    @Synchronized
    fun cancel(token: String): Reply {
        val draft = drafts[token] ?: return Reply.Refused(NO_SUCH, NO_DRAFT)
        return when (val state = draft.state) {
            State.Held, State.Armed -> {
                draft.stop(tracking)
                answer("say" to JsonPrimitive(STOPPED))
            }
            is State.Handed -> answer("say" to JsonPrimitive(tooLate(draft, state.at.atZone(clock.zone))))
            is State.Unsent -> answer("say" to JsonPrimitive(state.say))
            State.Stopped -> Reply.Refused(NO_SUCH, NO_DRAFT)
        }
    }

    /** The sends that count toward the limits, one per recipient: armed or handed to the phone, never stopped. */
    private fun counted(): List<Counted> = drafts.values.filter { it.state != State.Stopped }.flatMap { draft ->
        draft.confirmed?.let { at -> draft.to.map { Counted(it, at) } }.orEmpty()
    }

    // Under the one lock, so a cancel lands before the first text or after the last. One
    // recipient Android refuses never stops the next.
    @Synchronized
    private fun fire(draft: Draft) {
        if (draft.state != State.Armed) return
        val go = tracking?.going(draft.token) ?: Tracking.Go.SEND
        draft.state = after(go, clock.instant())
        if (go == Tracking.Go.SEND) draft.handOver(send, failed, tracking)
    }

    private fun answer(vararg pairs: Pair<String, JsonPrimitive>) = Reply.Answer(JsonObject(mapOf(*pairs)))

    companion object {
        /** What an armed text became when its delay ended, at [at]. */
        private fun after(go: Tracking.Go, at: Instant): State = when (go) {
            Tracking.Go.SEND -> State.Handed(at)
            Tracking.Go.NO_SUBSCRIPTION -> State.Unsent(UNSENT)
            Tracking.Go.REFUSED -> State.Unsent(WITHHELD)
            Tracking.Go.STOPPED -> State.Stopped
        }

        /**
         * The answer to a stop after the timer: when, and for several recipients, how many Android
         * took and each one it refused, named as the read-back named it.
         */
        private fun tooLate(draft: Draft, at: ZonedDateTime): String {
            val time = TIME.format(at)
            if (draft.to.size == 1) return "Too late to stop; it was handed to the phone at $time. $NOT_CONFIRMED"
            val refused = draft.refused.takeIf { it.isNotEmpty() }?.joinToString(" and ", "; it refused the text to ")
            val handed = "handed ${draft.handed} of ${draft.to.size} to Android at $time"
            return "Too late to stop; $handed${refused.orEmpty()}. $NOT_CONFIRMED"
        }

        /** What the read-back adds when it stopped [count] other held drafts. */
        private fun replaced(count: Int) = when {
            count <= 0 -> ""
            count == 1 -> " (replaces 1 other held draft)"
            else -> " (replaces $count other held drafts)"
        }

        /** How the read-back names [one]: the number, or the contact's name and the number, then any marks. */
        private fun readBack(one: Recipients.Resolved.To, recipients: Recipients?): String {
            val marks = recipients?.marks(one).orEmpty()
            return if (marks.isEmpty()) named(one) else marks.joinToString(", ", "${named(one)} (", ")")
        }

        /** How every answer names [one]: the number, or the contact's name and the number. */
        private fun named(one: Recipients.Resolved.To) = one.name?.let { "$it, ${one.number}" } ?: one.number

        /** Why [text] cannot be drafted, or null when it can. */
        private fun refusal(text: String): Reply.Refused? =
            if (text.isEmpty()) {
                Reply.Refused(EMPTY)
            } else {
                Limits.text(text)?.let { Reply.Refused(it, Limits.TEXT_REFUSED) }
            }

        /**
         * The outbox the phone uses: the phone's clock and zone, a timer thread of its own, and
         * tokens no one can guess. [send] and [failed] are the phone's radio and log, and [tracking]
         * keeps how each confirmed text ends.
         */
        fun onThePhone(send: (Handover) -> Unit, failed: (String) -> Unit, tracking: Tracking? = null): Outbox {
            val timer = Executors.newSingleThreadScheduledExecutor()
            return Outbox(
                clock = Clock.systemDefaultZone(),
                later = { wait, task -> timer.schedule(task, wait.toMillis(), TimeUnit.MILLISECONDS) },
                send = send,
                failed = failed,
                tokens = { UUID.randomUUID().toString() },
                tracking = tracking,
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
        private const val UNSENT = "It was not sent: the phone has no default SIM for texts."
        private const val WITHHELD = "It was not sent: the phone may no longer send texts."
        private const val NO_DEFAULT_SIM =
            "The phone has no default SIM for texts, so nothing is waiting to go. Choose one in Settings, " +
                "SIMs, Preferred SIM for SMS, then say yes again."
        private const val NOT_HELD =
            "The phone could not save the send, so nothing was sent. Free some storage or restart the app, " +
                "then say yes again."
        private const val CHANGED = "It changed; read it back first."
        private const val NO_SUCH = "There is no draft by that token waiting; make a new request."
        private const val NOT_A_NUMBER = "A text goes to one number in full international form, such as +4700000001."
        private const val EMPTY = "The text is empty; say what it should say."
    }
}
