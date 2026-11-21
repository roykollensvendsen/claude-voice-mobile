package org.claudevoice.mobile.plugins.sms_send.rules

import java.text.Normalizer

/**
 * A number in the owner's contacts, as the contacts capability gives it. [guessed] is the country
 * code put in front of a number saved without one, such as "+47"; null when the number was not a
 * guess.
 */
data class Contact(val name: String, val number: String, val label: String, val guessed: String? = null)

/**
 * Texting a contact by name (ADR-CVM-010, "By name"): which names a spoken query matches, and
 * whether what matched is one recipient, a choice for the owner, or no one. A query matches a
 * whole first name, a whole last name or the full name, ignoring case and accents; never a prefix,
 * a part of a name or a middle name, so the contact book cannot be walked a letter at a time.
 */
object Names {
    /** The most numbers one lookup answers, and so the most choices the owner hears. */
    const val MOST = 5

    /** The most characters of a contact's name the owner hears. */
    const val LONGEST = 40

    /**
     * A contact's name as the owner hears it, cleaned as a sender's is (`sms_since`): invisible
     * and control characters dropped, every run of spaces or line breaks one space, and cut to
     * [LONGEST] characters, counted as Unicode counts them. A name is the contact's own words, so
     * it can neither flood the answer nor turn a line around.
     */
    fun shown(name: String): String {
        val clean = SPACES.replace(HIDDEN.replace(name, ""), " ").trim()
        val count = clean.codePointCount(0, clean.length)
        return if (count > LONGEST) clean.substring(0, clean.offsetByCodePoints(0, LONGEST)).trimEnd() else clean
    }

    /** The fewest letters a query must have. */
    const val FEWEST_LETTERS = 2

    /** Protocol version 1's code for a name no contact has. */
    const val NOT_FOUND = "not_found"

    private const val MOBILE = "mobile"
    private const val SHOWN_DIGITS = 2
    private val MARKS = Regex("""\p{M}+""")
    private val SPACES = Regex("""[\s\p{Z}]+""")
    private val HIDDEN = Regex("""[[\p{Cc}\p{Cf}]&&[^\s]]""")
    private val LETTER = Regex("""\p{L}""")
    private val E164 = Regex("""\+[1-9][0-9]{6,14}""")

    /** What a lookup comes to. */
    sealed interface Decision {
        /** One mobile number, not guessed: the draft goes to it. */
        data class One(val contact: Contact) : Decision

        /** Anything else that matched: the owner picks one of these; [more] when others matched. */
        data class Choose(val contacts: List<Contact>, val more: Boolean = false) : Decision

        /** No contact by that name. */
        data object None : Decision
    }

    /** How many letters [query] has, of any script, with no digit, space or mark counted. */
    fun letters(query: String): Int = LETTER.findAll(fold(query)).count()

    /** Whether [query] is the whole first name, the whole last name or the full name in [name]. */
    fun matches(query: String, name: String): Boolean {
        val words = words(name)
        val asked = words(query).joinToString(" ")
        return words.isNotEmpty() && asked in setOf(words.first(), words.last(), words.joinToString(" "))
    }

    /**
     * One recipient when [found] holds one mobile number, a choice of at most [MOST] when it holds
     * anything else, and no one when it holds nothing. [found] is every match. First a number not
     * in full international form is left out, since it could not be sent to, and rows with the same
     * number are one, as a contact synced from several accounts gives it several times; the cap
     * comes after, so neither can crowd out a real number.
     */
    fun decide(found: List<Contact>): Decision {
        val usable = distinct(found)
        return when {
            usable.isEmpty() -> Decision.None
            usable.size == 1 && sure(usable[0]) -> Decision.One(usable[0])
            else -> Decision.Choose(usable.take(MOST), more = usable.size > MOST)
        }
    }

    /**
     * One row for each number that can be sent to, in the order found. Of rows with one number, a
     * mobile one wins, and then one whose number was not guessed.
     */
    private fun distinct(found: List<Contact>): List<Contact> {
        val kept = LinkedHashMap<String, Contact>()
        for (row in found) {
            val had = kept[row.number]
            if (E164.matches(row.number) && (had == null || rank(row) > rank(had))) kept[row.number] = row
        }
        return kept.values.toList()
    }

    private fun rank(row: Contact): Int = (if (row.label == MOBILE) 2 else 0) + (if (row.guessed == null) 1 else 0)

    /** A number the draft may go to without asking: a mobile one, not guessed. */
    private fun sure(row: Contact): Boolean = row.label == MOBILE && row.guessed == null

    /**
     * A choice in words: the name, and the label with the last two digits, never the number; and
     * for a guessed number, the code that was guessed.
     */
    fun choice(contact: Contact): String =
        "${shown(contact.name)}, ${contact.label} ending ${contact.number.takeLast(SHOWN_DIGITS)}" +
            (contact.guessed?.let { ", guessed $it" } ?: "")

    private fun words(text: String): List<String> = fold(text).split(SPACES).filter { it.isNotEmpty() }

    private fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
}
