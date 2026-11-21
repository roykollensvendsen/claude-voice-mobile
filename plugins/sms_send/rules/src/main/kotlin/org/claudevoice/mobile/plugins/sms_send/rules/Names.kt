package org.claudevoice.mobile.plugins.sms_send.rules

import java.text.Normalizer

/** A number in the owner's contacts, as the contacts capability gives it. */
data class Contact(val name: String, val number: String, val label: String)

/**
 * Texting a contact by name (ADR-CVM-010, "By name"): which names a spoken query matches, and
 * whether what matched is one recipient, a choice for the owner, or no one. A query matches a
 * whole first name, a whole last name or the full name, ignoring case and accents; never a prefix,
 * a part of a name or a middle name, so the contact book cannot be walked a letter at a time.
 */
object Names {
    /** The most numbers one lookup answers, and so the most choices the owner hears. */
    const val MOST = 5

    /** The fewest letters a query must have. */
    const val FEWEST_LETTERS = 2

    /** Protocol version 1's code for a name no contact has. */
    const val NOT_FOUND = "not_found"

    private const val MOBILE = "mobile"
    private const val SHOWN_DIGITS = 2
    private val MARKS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")
    private val LETTER = Regex("""\p{L}""")
    private val E164 = Regex("""\+[1-9][0-9]{6,14}""")

    /** What a lookup comes to. */
    sealed interface Decision {
        /** One mobile number: the draft goes to it. */
        data class One(val contact: Contact) : Decision

        /** Anything else that matched: the owner picks one of these. */
        data class Choose(val contacts: List<Contact>) : Decision

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
     * anything else, and no one when it holds nothing. A number not in full international form is
     * left out first, since it could not be sent to.
     */
    fun decide(found: List<Contact>): Decision {
        val usable = found.filter { E164.matches(it.number) }
        return when {
            usable.isEmpty() -> Decision.None
            usable.size == 1 && usable[0].label == MOBILE -> Decision.One(usable[0])
            else -> Decision.Choose(usable.take(MOST))
        }
    }

    /** A choice in words: the name, and the label with the last two digits, never the number. */
    fun choice(contact: Contact): String =
        "${contact.name}, ${contact.label} ending ${contact.number.takeLast(SHOWN_DIGITS)}"

    private fun words(text: String): List<String> = fold(text).split(SPACES).filter { it.isNotEmpty() }

    private fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
}
