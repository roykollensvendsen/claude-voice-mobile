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
 * gives every number whose contact's name `matches` accepts, or null when the phone may
 * not read contacts; such a call is no lookup. [tokens] makes picks no one can guess.
 */
class Recipients(
    private val clock: Clock,
    private val tokens: () -> String,
    private val contact: (String) -> Boolean? = { null },
    private val texted: (String) -> Boolean? = { null },
    private val find: (matches: (String) -> Boolean) -> List<Contact>?,
) {
    /** What `to` comes to: a number, with the contact's name when it came from one, or an answer. */
    sealed interface Resolved {
        data class To(val number: String, val name: String?) : Resolved

        data class Answered(val reply: Reply) : Resolved
    }

    /** What a list of `to` comes to: a number for each, in order, or the answer the owner hears instead. */
    sealed interface Group {
        data class To(val all: List<Resolved.To>) : Group

        data class Answered(val reply: Reply) : Group
    }

    private val lookups = mutableListOf<Instant>()
    private var picks = mapOf<String, Contact>()

    /** The number [to] names, or the answer the owner hears instead. */
    @Synchronized
    fun resolve(to: String): Resolved = when (val group = resolve(listOf(to))) {
        is Group.Answered -> Resolved.Answered(group.reply)
        is Group.To -> group.all.single()
    }

    /**
     * The numbers [to] names, in order, or the answer about the first that needs one. When that
     * answer is choices, the picks this list already used stay open with them, so the next
     * request, with its new pick, keeps the earlier ones (ADR-CVM-010, "Several recipients").
     */
    @Synchronized
    fun resolve(to: List<String>): Group {
        val open = picks
        picks = emptyMap()
        return tooMany(to.filter { it !in open && looksUp(it) }.size)?.let { Group.Answered(it) } ?: lookedUp(to, open)
    }

    private fun lookedUp(to: List<String>, open: Map<String, Contact>): Group {
        val all = mutableListOf<Resolved.To>()
        for (each in to) {
            val picked = open[each]
            when (val one = picked?.let { Resolved.To(it.number, Names.shown(it.name)) } ?: one(each)) {
                is Resolved.To -> all += one
                is Resolved.Answered -> {
                    if (picks.isNotEmpty()) picks = open.filterKeys { it in to } + picks
                    return Group.Answered(one.reply)
                }
            }
        }
        return Group.To(all)
    }

    private fun one(to: String): Resolved {
        return when {
            to.startsWith(PICK) -> refused(STALE, UNKNOWN_PICK)
            !LETTER.containsMatchIn(to) -> Resolved.To(to, null)
            Names.letters(to) < Names.FEWEST_LETTERS -> refused(TOO_SHORT, Outbox.INVALID_RECIPIENT)
            recent() >= LOOKUPS -> refused(TOO_MANY, Limits.RATE_LIMITED)
            else -> looked(to) ?: refused(NO_CONTACTS, SmsSend.PERMISSION_MISSING)
        }
    }

    /**
     * The refusal for a list whose [needed] name lookups would pass the lookups left this hour,
     * given before any lookup is made, or null when they fit (ADR-CVM-010, "By name"). A single
     * name keeps its own refusal, made when it is looked up.
     */
    private fun tooMany(needed: Int): Reply.Refused? {
        val left = LOOKUPS - recent()
        return if (needed > 1 && needed > left) {
            Reply.Refused(
                "Not drafted: these names need $needed lookups; lookups left this hour: $left. " +
                    "Say the numbers instead.",
                Limits.RATE_LIMITED,
            )
        } else {
            null
        }
    }

    /**
     * The marks the read-back gives [one], so a stray number stands out (ADR-CVM-010, "Several
     * recipients"). A number given as a number is one check of the contacts and the sent box
     * together, and that check is one of the lookups an hour; past them it is "not checked", and
     * neither is read. A contact found by name has used its lookup already, so only the sent box
     * is read for it. A reading the owner has not allowed gives no mark and no claim.
     */
    @Synchronized
    fun marks(one: Resolved.To): List<String> = when {
        one.name != null -> listOfNotNull(NEVER_TEXTED.takeIf { texted(one.number) == false })
        recent() >= LOOKUPS -> listOf(NOT_CHECKED)
        else -> {
            val known = contact(one.number)
            val sent = texted(one.number)
            if (known != null || sent != null) lookups += clock.instant()
            listOfNotNull(NOT_IN_CONTACTS.takeIf { known == false }, NEVER_TEXTED.takeIf { sent == false })
        }
    }

    /** Closes the open choices, as any request does, even one refused before `to` is read. */
    @Synchronized
    fun close() {
        picks = emptyMap()
    }

    /** The lookups made in the last hour. */
    private fun recent(): Int {
        val since = clock.instant().minus(HOUR)
        lookups.removeAll { it <= since }
        return lookups.size
    }

    private fun looked(name: String): Resolved? {
        val found = find { Names.matches(name, it) } ?: return null
        lookups += clock.instant()
        return when (val decision = Names.decide(found)) {
            is Names.Decision.One -> Resolved.To(decision.contact.number, Names.shown(decision.contact.name))
            is Names.Decision.Choose -> choose(decision.contacts, decision.more)
            Names.Decision.None ->
                refused("No contact is called $name; say another name or the number.", Names.NOT_FOUND)
        }
    }

    private fun choose(contacts: List<Contact>, more: Boolean): Resolved {
        picks = contacts.associateBy { PICK + tokens() }
        val words = picks.values.map(Names::choice)
        val items = picks.keys.zip(words) { pick, say ->
            JsonObject(mapOf("pick" to JsonPrimitive(pick), "say" to JsonPrimitive(say)))
        }
        val say = words.withIndex().joinToString("; ", "Which one? ", ".") { (i, it) -> "${i + 1}: $it" } +
            (if (more) " $MORE" else "")
        return Resolved.Answered(
            Reply.Answer(JsonObject(mapOf("choices" to JsonArray(items), "say" to JsonPrimitive(say)))),
        )
    }

    companion object {
        /** The recipients the phone uses: the phone's clock, and picks no one can guess. */
        fun onThePhone(find: (matches: (String) -> Boolean) -> List<Contact>?): Recipients =
            Recipients(Clock.systemDefaultZone(), { UUID.randomUUID().toString() }, find = find)

        private fun refused(say: String, code: String) = Resolved.Answered(Reply.Refused(say, code))

        /** Whether [to] is a name that will be looked up, rather than a pick, a number or too short. */
        private fun looksUp(to: String) =
            !to.startsWith(PICK) && LETTER.containsMatchIn(to) && Names.letters(to) >= Names.FEWEST_LETTERS

        /** Each `to` as a number, with no lookup: the outbox's own reading when no contacts are wired. */
        fun numbers(to: List<String>): Group = Group.To(to.map { Resolved.To(it, null) })

        /** The most name lookups in an hour. */
        const val LOOKUPS = 10

        /** The code for a pick that is not from the latest choices. */
        const val UNKNOWN_PICK = "unknown_pick"

        const val NO_CONTACTS =
            "The phone may not read contacts. Allow it in Settings, Apps, claude-voice, Permissions, Contacts, " +
                "or say the number instead."

        private const val PICK = "pick-"
        private const val NOT_IN_CONTACTS = "not in your contacts"
        private const val NEVER_TEXTED = "never texted before"
        private const val NOT_CHECKED = "not checked"
        private val HOUR: Duration = Duration.ofHours(1)
        private val LETTER = Regex("""\p{L}""")
        private const val STALE = "That choice is from another request; say the name again."
        private const val TOO_SHORT = "Say at least two letters of a name, or the number."
        private const val MORE = "And more match; say the full name."
        private const val TOO_MANY = "Not drafted: too many name lookups; say the number instead."
    }
}
