package org.claudevoice.mobile.plugins.sms.rules

/**
 * A message's sender as the hub hears it (ADR-CVM-010). A letter-named sender is chosen by
 * whoever sends, so it is cleaned before anything reads it out: invisible and control characters
 * are dropped, every kind of space or line break becomes one space, and it is cut to [LONGEST]
 * characters. So a name can neither flood what the hub hears nor turn a line around.
 */
object Senders {
    /** The most characters of a sender kept, counted as Unicode counts them. */
    const val LONGEST = 40

    /** The sender of a message the network gave none for. */
    const val UNKNOWN = "an unknown sender"

    /** What the sender is, by the answer's names for it. */
    enum class Kind(val wire: String) { NUMBER("number"), SHORT_CODE("short_code"), NAME("name") }

    /** A sender as it is kept: cleaned, a national number put in E.164 form, and its kind. */
    data class Heard(val from: String, val kind: Kind)

    // Spaces and line breaks of every kind, which become one space.
    private val SPACES = Regex(CharacterSets.WHITE_SPACE + "+")

    // Control and format characters that are not spaces, such as a zero-width space or a mark
    // that turns the text around; they are dropped.
    private val HIDDEN = Regex(CharacterSets.HIDDEN)
    private val E164 = Regex("\\+[0-9]+")
    private val DIGITS = Regex("[0-9]+")

    // What people put between a number's digits; dropped only when the rest is a number.
    private val SEPARATORS = Regex("[ ().-]")

    /** The sender cleaned and cut, or [UNKNOWN] when nothing is left. */
    fun shown(sender: String): String {
        val clean = sender.replace(HIDDEN, "").replace(SPACES, " ").trim()
        if (clean.isEmpty()) return UNKNOWN
        val count = clean.codePointCount(0, clean.length)
        return if (count > LONGEST) clean.substring(0, clean.offsetByCodePoints(0, LONGEST)).trimEnd() else clean
    }

    /**
     * The sender as it is kept. A number written with spaces, dashes, dots or brackets is kept
     * without them. A run of seven digits or more with no "+" is a national number, which
     * [toE164] puts in E.164 form with the phone's country; when it cannot, or gives anything
     * else, the digits are kept as they came, still a number.
     */
    fun heard(sender: String, toE164: (String) -> String?): Heard {
        val from = compact(shown(sender))
        return when {
            E164.matches(from) -> Heard(from, Kind.NUMBER)
            !DIGITS.matches(from) -> Heard(from, Kind.NAME)
            from.length <= OneTimeCodes.SHORT_CODE_DIGITS -> Heard(from, Kind.SHORT_CODE)
            else -> Heard(toE164(from)?.takeIf { E164.matches(it) } ?: from, Kind.NUMBER)
        }
    }

    private fun compact(shown: String): String {
        val bare = shown.replace(SEPARATORS, "")
        return if (E164.matches(bare) || DIGITS.matches(bare)) bare else shown
    }
}
