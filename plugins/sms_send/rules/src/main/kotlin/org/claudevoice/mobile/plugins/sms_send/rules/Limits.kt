package org.claudevoice.mobile.plugins.sms_send.rules

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

    // The endings a bare domain is taken as a link by, in any case. A short list, so an
    // abbreviation such as "f.eks." or "ca.ti" is no link.
    private val ENDINGS = listOf(
        "no", "com", "net", "org", "io", "se", "dk", "fi", "de", "uk", "eu", "info", "biz", "app", "dev",
        "me", "co", "tv", "xyz", "ly", "gl", "to",
    )

    // A scheme (https://) or "www.", in any case, or a domain, as "example.no", "Vg.No" or
    // "name@example.no", whose ending is on the list or which a "/" follows, as "name.any/x".
    private val LINK = Regex(
        """(?i:[a-z][a-z0-9+.-]*://|www\.)|""" +
            """(?<![\p{L}\p{N}])[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.""" +
            """(?:(?i:${ENDINGS.joinToString("|")})(?![\p{L}\p{N}])|\p{L}{2,63}/)""",
    )

    // More than MAX_DIGITS digits of any script, apart only by spaces, dots or dashes, so a
    // number written "123 456 789" is one run of nine.
    private val DIGITS = Regex("""\p{Nd}(?:[\s\p{Zs}.\-‐-―−]*\p{Nd}){$MAX_DIGITS,}""")

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
        return when {
            length > MAX_LENGTH ->
                "Not drafted: a text can be at most $MAX_LENGTH characters, and this one has $length."
            LINK.containsMatchIn(text) -> "Not drafted: texts can't contain links or web addresses."
            DIGITS.containsMatchIn(text) ->
                "Not drafted: texts can't contain more than $MAX_DIGITS digits in a row, " +
                    "such as a phone or account number."
            else -> null
        }
    }

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
}
