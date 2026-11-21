package org.claudevoice.mobile.plugins.sms_send.rules

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who a request's `to` names (ADR-CVM-010, "By name"): a number as it is, a contact's name looked
 * up, or a `pick` from the latest choices. A name with one mobile number is that number; anything
 * else that matched answers choices, each an opaque pick bound to that answer and never the whole
 * number. A new answer, or any draft, closes the choices before it, so an older pick is refused.
 *
 * At most [LOOKUPS] lookups an hour are made, counted in memory, so a restart starts again. [find]
 * gives at most `most` numbers whose contact's name `matches` accepts, or null when the phone may
 * not read contacts; such a call is no lookup. [tokens] makes picks no one can guess.
 */
class Recipients(
    private val clock: Clock,
    private val tokens: () -> String,
    private val find: (matches: (String) -> Boolean, most: Int) -> List<Contact>?,
) {
    /** What `to` comes to: a number, with the contact's name when it came from one, or an answer. */
    sealed interface Resolved {
        data class To(val number: String, val name: String?) : Resolved

        data class Answered(val reply: Reply) : Resolved
    }

    private val lookups = mutableListOf<Instant>()
    private var picks = mapOf<String, Contact>()

    /** The number [to] names, or the answer the owner hears instead. */
    @Synchronized
    fun resolve(to: String): Resolved {
        val picked = picks[to]
        picks = emptyMap()
        return when {
            picked != null -> Resolved.To(picked.number, picked.name)
            to.startsWith(PICK) -> refused(STALE, UNKNOWN_PICK)
            !LETTER.containsMatchIn(to) -> Resolved.To(to, null)
            Names.letters(to) < Names.FEWEST_LETTERS -> refused(TOO_SHORT, Outbox.INVALID_RECIPIENT)
            blocked() -> refused(TOO_MANY, Limits.RATE_LIMITED)
            else -> looked(to) ?: refused(NO_CONTACTS, SmsSend.PERMISSION_MISSING)
        }
    }

    private fun blocked(): Boolean {
        val since = clock.instant().minus(HOUR)
        lookups.removeAll { it <= since }
        return lookups.size >= LOOKUPS
    }

    private fun looked(name: String): Resolved? {
        val found = find({ Names.matches(name, it) }, Names.MOST) ?: return null
        lookups += clock.instant()
        return when (val decision = Names.decide(found)) {
            is Names.Decision.One -> Resolved.To(decision.contact.number, decision.contact.name)
            is Names.Decision.Choose -> choose(decision.contacts)
            Names.Decision.None ->
                refused("No contact is called $name; say another name or the number.", Names.NOT_FOUND)
        }
    }

    private fun choose(contacts: List<Contact>): Resolved {
        picks = contacts.associateBy { PICK + tokens() }
        val words = picks.values.map(Names::choice)
        val items = picks.keys.zip(words) { pick, say ->
            JsonObject(mapOf("pick" to JsonPrimitive(pick), "say" to JsonPrimitive(say)))
        }
        val say = words.withIndex().joinToString("; ", "Which one? ", ".") { (i, it) -> "${i + 1}: $it" }
        return Resolved.Answered(
            Reply.Answer(JsonObject(mapOf("choices" to JsonArray(items), "say" to JsonPrimitive(say)))),
        )
    }

    private fun refused(say: String, code: String) = Resolved.Answered(Reply.Refused(say, code))

    companion object {
        /** The recipients the phone uses: the phone's clock, and picks no one can guess. */
        fun onThePhone(find: (matches: (String) -> Boolean, most: Int) -> List<Contact>?): Recipients =
            Recipients(Clock.systemDefaultZone(), { UUID.randomUUID().toString() }, find)

        /** `to` as a number, with no lookup: the outbox's own reading when no contacts are wired. */
        fun number(to: String): Resolved = Resolved.To(to, null)

        /** The most name lookups in an hour. */
        const val LOOKUPS = 10

        /** The code for a pick that is not from the latest choices. */
        const val UNKNOWN_PICK = "unknown_pick"

        const val NO_CONTACTS =
            "The phone may not read contacts. Allow it in Settings, Apps, claude-voice, Permissions, Contacts, " +
                "or say the number instead."

        private const val PICK = "pick-"
        private val HOUR: Duration = Duration.ofHours(1)
        private val LETTER = Regex("""\p{L}""")
        private const val STALE = "That choice is from another request; say the name again."
        private const val TOO_SHORT = "Say at least two letters of a name, or the number."
        private const val TOO_MANY = "Not drafted: too many name lookups; say the number instead."
    }
}
