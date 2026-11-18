package org.claudevoice.mobile.plugins.sms.rules

import java.text.Normalizer

/** A text message as the hub may see it (ADR-CVM-010). */
sealed interface Shown {
    /** The message as it came. */
    data class Text(val body: String) : Shown

    /** A message that may hold a one-time code: the hub gets only [line], never the text. */
    data class Hidden(val sender: String, val line: String) : Shown
}

/**
 * ADR-CVM-010: one-time codes never leave the phone. A message is hidden whole, and the rules
 * hide too much rather than too little: the owner still reads every message in the phone's own
 * text app.
 *
 * - From a short code or a sender named with letters, any run of 4 to 8 digits hides it.
 * - From any sender, a code word with a digit on its own line, the line before or the line after
 *   hides it, so "123456\nis your code" is caught as well as "Your code:\n123456".
 *
 * Text is first put in Unicode's compatibility form (NFKC), so fullwidth letters and digits,
 * and odd spaces, read as plain ones. A digit is any decimal digit, in any script.
 */
object OneTimeCodes {
    /** ADR-CVM-010's code words, matched in any case and inside longer words. */
    private val WORDS =
        listOf(
            "code", "kode", "otp", "one-time", "passcode", "verification", "bekreftelse", "passord", "pin",
            "engangskode", "sikkerhetskode", "logg inn", "login", "bankid", "token",
        )

    /**
     * The most digits a short code has. At six, an ordinary eight-digit mobile number written
     * without its plus is not taken for one; the cost is that a code with no code word from a
     * seven- or eight-digit service number is kept. The owner may set it otherwise; the tests
     * follow from this one line.
     */
    const val SHORT_CODE_DIGITS = 6
    private const val FEWEST_CODE_DIGITS = 4
    private const val MOST_CODE_DIGITS = 8
    private val RUN_DIGITS = FEWEST_CODE_DIGITS..MOST_CODE_DIGITS
    private val LINE_END = Regex("\r\n|\n|\r")
    private val SPACES = Regex("(?U)\\s+")
    private val DIGIT = Regex("\\p{Nd}")

    // A run of digits on its own, and the same with single spaces, dots or dashes between groups.
    private val PLAIN_RUN = Regex("\\p{Nd}+")
    private val GROUPED_RUN = Regex("\\p{Nd}+(?:[ .\\-]\\p{Nd}+)*")

    /** The message as the hub may see it: kept, or hidden as "a one-time code from <sender>". */
    fun shown(sender: String, body: String): Shown =
        if (hides(sender, plain(body))) {
            Shown.Hidden(sender, "a one-time code from ${sender.ifBlank { "an unknown sender" }}")
        } else {
            Shown.Text(body)
        }

    /** A sender named with letters, a short code of at most six digits with no plus, or none at all. */
    fun namedOrShort(sender: String): Boolean {
        val trimmed = plain(sender).trim()
        return trimmed.isEmpty() ||
            trimmed.any { it.isLetter() } ||
            (!trimmed.contains('+') && trimmed.count { it.isDigit() } <= SHORT_CODE_DIGITS)
    }

    private fun plain(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)

    private fun hides(sender: String, body: String): Boolean =
        (namedOrShort(sender) && hasCodeLengthRun(body)) || hasDigitNearWord(body)

    private fun hasCodeLengthRun(body: String): Boolean =
        (PLAIN_RUN.findAll(body) + GROUPED_RUN.findAll(body)).any { run ->
            run.value.count { it.isDigit() } in RUN_DIGITS
        }

    private fun hasDigitNearWord(body: String): Boolean {
        val lines = body.split(LINE_END).map { it.lowercase().replace(SPACES, " ") }
        return lines.indices.any { i ->
            val line = lines[i]
            WORDS.any { word -> line.contains(word) } &&
                (i - 1..i + 1).any { near -> DIGIT.containsMatchIn(lines.getOrElse(near) { "" }) }
        }
    }
}
