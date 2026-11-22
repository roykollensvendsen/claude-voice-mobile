package org.claudevoice.mobile.plugins.sms_send.rules

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A send that counts toward the limits: to whom, and when it was confirmed. */
data class Counted(val to: String, val at: Instant)

/** A limit that blocks one more send: its words, and when it lifts. */
data class Blocked(val words: String, val until: Instant) {
    /** The refusal the owner hears, as "Not sent: that's 10 texts in the last hour; try again after 09.30." */
    fun say(lead: String, zone: ZoneId): String = "$lead: $words; try again after ${TIME.format(until.atZone(zone))}."

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm")
    }
}

/** A group of [of] checked against the limits: the first limit that blocks one of it, if any, and how many fit. */
class GroupCheck(private val blocked: Blocked?, private val fit: Int, private val of: Int) {
    /**
     * The refusal when the group does not all fit, as "Not sent: that's 10 texts in the last hour;
     * try again after 09.30. 2 of the 3 would go now.", or null when it does. A group of one keeps
     * the words of one text.
     */
    fun refused(lead: String, zone: ZoneId): Reply.Refused? = blocked?.let {
        val more = if (of > 1) " $fit of the $of would go now." else ""
        Reply.Refused(it.say(lead, zone) + more, Limits.RATE_LIMITED)
    }
}

/**
 * What may be sent, and how often (messages-v1 §8, choice B, with the limits the owner added).
 * A text is checked when it is drafted. The counts are checked when it is drafted, so the owner
 * hears it early, and again when it is confirmed.
 */
object Limits {
    /** Protocol version 1's code for a send refused because too many went lately. */
    const val RATE_LIMITED = "rate_limited"

    /** Protocol version 1's code for a text refused for what it holds. */
    const val TEXT_REFUSED = "text_refused"

    private const val MAX_LENGTH = 1000
    private const val MAX_DIGITS = 6

    private const val DAY = """(?:0?[1-9]|[12][0-9]|3[01])"""
    private const val MONTH = """(?:0?[1-9]|1[0-2])"""
    private const val YEAR = """(?:19|20)[0-9]{2}"""
    private const val YEARS = """(?:(?:19|20)[0-9]{2}|[0-9]{2})"""
    private const val TIME = """(?:[01]?[0-9]|2[0-3])[.:][0-5][0-9]"""

    // The only words with a dot that are no link: a whole word that is one of these
    // abbreviations, in any case, with or without one dot after. This list only ever exempts
    // abbreviations. Never add a web ending to it: the rule fails closed on any ending.
    private val ABBREVIATIONS = setOf(
        "f.eks", "bl.a", "m.m", "o.l", "osv", "dvs", "evt", "inkl", "jf", "pga", "tlf",
        "nr", "kl", "mht", "ifm", "iht", "mv", "ca", "e.g", "i.e", "etc",
    )

    // A scheme (https://) or "www.", in any case; a dotted word whose last part is 2 to 63
    // letters in any case, as "example.com", "Vg.No", "pay.click" or "name@host.any"; or a
    // dotted word a "/" follows, as "10.0.0.1/x". A name may hold symbols and marks, as a
    // heart in "i❤.ws", since the receiving phone makes a link of those too.
    private val LINK = Regex(
        """(?i:[a-z][a-z0-9+.-]*://|www\.)|[\p{L}\p{N}\p{S}\p{M}-]\.\p{L}{2,63}(?![\p{L}\p{N}])|""" +
            """[\p{L}\p{N}\p{S}\p{M}-]+(?:\.[\p{L}\p{N}\p{S}\p{M}-]+)+/""",
    )

    // An address written as four numbers, as "10.0.0.1"; three, as a date or a version, is none.
    private val QUAD = Regex("""(?<![\p{N}.])\p{N}{1,3}(?:\.\p{N}{1,3}){3}(?!\p{N}|\.\p{N})""")

    // The ideographic full stop NFKC leaves, which a browser takes as a dot between names.
    private val IDEOGRAPHIC_DOT = Regex("""\u3002""")

    // Format characters (category Cf), as a zero-width space or a direction mark.
    private val INVISIBLE = Regex("""\p{Cf}""")

    // The two places a format character is part of an emoji, and can't split a link: a joiner
    // (U+200D) between two symbols, the first maybe with its emoji form or skin tone after, as in
    // a family; and the tags that make the black flag a part of a country, as England's. Every
    // other format character still refuses the text.
    private val EMOJI_JOINER = Regex("""(\p{So}[\x{FE0F}\x{1F3FB}-\x{1F3FF}]?)\x{200D}(?=\p{So})""")
    private val TAG_FLAG = Regex("""\x{1F3F4}[\x{E0020}-\x{E007E}]+\x{E007F}""")

    private val SPACE = Regex("""[\s\p{Zs}]+""")

    // Brackets, quotes and marks a word may stand inside or end with, as "(f.eks." or "bl.a.,".
    private val WRAP = Regex("""^[()\[\]{}"'«»“”‘’]+|[()\[\]{}"'«»“”‘’,;:!?]+$""")

    private fun link(word: String): Boolean {
        val bare = WRAP.replace(word, "")
        return QUAD.containsMatchIn(bare) ||
            bare.lowercase().removeSuffix(".") !in ABBREVIATIONS && LINK.containsMatchIn(bare)
    }

    // More than MAX_DIGITS digits of any script, apart only by spaces, dots, dashes, slashes,
    // colons, commas, brackets, underscores, apostrophes or middle dots, so a number written
    // "123 456 789" or "(412) 34 567" is one run.
    private val DIGITS = Regex("""\p{Nd}(?:[\s\p{Zs}.\-‐-―−/:,()\[\]_'’·]*\p{Nd}){$MAX_DIGITS,}""")

    // The dates and times ADR-CVM-010 takes out before a run is counted, in ASCII digits only:
    // two times joined by a dash, year-month-day, day.month.year or day/month/year, a time with
    // a dot or a colon, and day.month. Just outside, a shape touches no digit, dot, slash or dash,
    // but for one closing mark at its end.
    private val DATE_OR_TIME = Regex(
        """(?<![\p{Nd}./\-‐-―−])(?:$TIME[-–]$TIME|$YEAR-$MONTH-$DAY|$DAY\.$MONTH\.$YEARS|""" +
            """$DAY/$MONTH/$YEARS|$TIME|$DAY\.$MONTH)(?=[.,!?)]?(?![\p{Nd}./\-‐-―−]))""",
    )

    // A word on each side of a shape, skipping spaces, holds a letter and no digit, or is the edge.
    private val PLAIN_WORD = Regex("""[^\p{Nd}]*\p{L}[^\p{Nd}]*""")
    private val CLOSING = Regex("""^[.,!?)]""")

    private const val PER_HOUR = 10
    private const val PER_DAY = 30
    private const val PER_NUMBER = 3
    private const val NUMBER_MINUTES = 10L

    // In this order, so that of two limits that lift at the same moment, the first is named.
    private val RULES = listOf(
        Rule(PER_HOUR, Duration.ofHours(1), recipient = false, "that's $PER_HOUR texts in the last hour"),
        Rule(PER_DAY, Duration.ofDays(1), recipient = false, "that's $PER_DAY texts in the last 24 hours"),
        Rule(
            PER_NUMBER,
            Duration.ofMinutes(NUMBER_MINUTES),
            recipient = true,
            "that's $PER_NUMBER texts to that number in $NUMBER_MINUTES minutes",
        ),
    )

    private class Rule(val most: Int, val window: Duration, val recipient: Boolean, val words: String)

    /** Why [text] may not be drafted, in words the owner hears, or null when it may. */
    fun text(text: String): String? {
        val length = text.codePointCount(0, text.length)
        return if (length > MAX_LENGTH) {
            "Not drafted: a text can be at most $MAX_LENGTH characters, and this one has $length."
        } else {
            val read = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(IDEOGRAPHIC_DOT, ".")
            held(TAG_FLAG.replace(EMOJI_JOINER.replace(read, "$1"), "\uD83C\uDFF4"))
        }
    }

    /**
     * Why what [text] holds may not be drafted, read as a browser reads it: wide letters as plain
     * ones and look-alike dots as dots (NFKC first). An invisible character could split a link
     * the rule would otherwise see, so any one outside an emoji refuses the text.
     */
    private fun held(text: String): String? =
        when {
            INVISIBLE.containsMatchIn(text) ->
                "Not drafted: the text has a hidden character. Leave out any emoji, or say it again."
            SPACE.toPattern().splitAsStream(text).anyMatch(::link) ->
                "Not drafted: texts can't contain links or web addresses."
            DIGITS.containsMatchIn(withoutDates(text)) ->
                "Not drafted: texts can't contain more than $MAX_DIGITS digits in a row, " +
                    "such as a phone or account number."
            else -> null
        }

    /**
     * [text] with each date and time that stands alone taken out, leaving a break, so the digits on
     * its two sides are never counted as one run (ADR-CVM-010, "Dates and times in a text").
     */
    private fun withoutDates(text: String): String =
        DATE_OR_TIME.replace(text) { if (alone(text, it.range)) " x " else it.value }

    private fun alone(text: String, at: IntRange): Boolean {
        val before = text.substring(0, at.first).trimEnd().split(SPACE).last()
        val after = text.substring(at.last + 1).replaceFirst(CLOSING, "").trimStart().split(SPACE).first()
        return plain(before) && plain(after)
    }

    private fun plain(word: String) = word.isEmpty() || PLAIN_WORD.matches(word)

    /**
     * The limit that keeps one more send to [to] from going [now], given the sends that still
     * count, or null when it may go. When several block, the one that lifts last.
     */
    fun blocked(counted: List<Counted>, to: String, now: Instant): Blocked? = RULES.mapNotNull { rule ->
        val times = counted
            .filter { (!rule.recipient || it.to == to) && it.at > now.minus(rule.window) }
            .map { it.at }
            .sorted()
        if (times.size < rule.most) null else Blocked(rule.words, times[times.size - rule.most].plus(rule.window))
    }.maxByOrNull { it.until }

    /**
     * Each of [numbers] checked in turn as one more send [now], every one that fits counting
     * toward the next (ADR-CVM-010, "Several recipients"): the first limit that blocks one, and
     * how many fit.
     */
    fun group(counted: List<Counted>, numbers: List<String>, now: Instant): GroupCheck {
        val sends = counted.toMutableList()
        val blocks = numbers.mapNotNull { number ->
            blocked(sends, number, now).also { if (it == null) sends += Counted(number, now) }
        }
        return GroupCheck(blocks.firstOrNull(), numbers.size - blocks.size, numbers.size)
    }
}
