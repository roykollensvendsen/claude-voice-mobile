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
 * Text is first put in Unicode's compatibility form, split (NFKD), so fullwidth letters and
 * digits, and odd spaces, read as plain ones. Accents and other combining marks, and invisible
 * format characters, are then dropped, and every dash reads as "-". A digit is any decimal
 * digit, in any script.
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
    private val LETTER = Regex("\\p{L}")
    // Invisible format characters, such as a zero-width space or a soft hyphen.
    private val FORMAT = Regex("\\p{Cf}")

    // Combining marks: accents once split from their letters, and invisible marks such as the
    // combining grapheme joiner and every variation selector.
    private val MARK = Regex("\\p{Mn}")

    // Dashes, and the minus sign, which is a math symbol rather than a dash.
    private val DASH = Regex("[\\p{Pd}\\u2212]")
    // The words hold no character a pattern treats as special, so they are joined as they are.
    private val CODE_WORD = Regex(WORDS.joinToString("|"))

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
            LETTER.containsMatchIn(trimmed) ||
            (!trimmed.contains('+') && DIGIT.findAll(trimmed).count() <= SHORT_CODE_DIGITS)
    }

    // NFKD splits every accent from its letter, so dropping the marks leaves "códe" as "code". It
    // keeps format characters and turns odd dashes into other dashes, so both are dealt with after
    // it: a code word stays a code word. The code words are plain letters, which this leaves as
    // they are; the code-word test sends each one through it.
    private fun plain(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(MARK, "")
            .replace(FORMAT, "")
            .replace(DASH, "-")

    private fun hides(sender: String, body: String): Boolean =
        (namedOrShort(sender) && hasCodeLengthRun(body)) || hasDigitNearWord(body)

    private fun hasCodeLengthRun(body: String): Boolean =
        (PLAIN_RUN.findAll(body) + GROUPED_RUN.findAll(body)).any { run ->
            run.value.count { it.isDigit() } in RUN_DIGITS
        }

    // Plain loops rather than Kotlin's inlined `any`, so each comparison is a line of ours that
    // Pitest can mutate and a test can catch.
    private fun hasDigitNearWord(body: String): Boolean {
        val lines = body.split(LINE_END).map { it.lowercase().replace(SPACES, " ") }
        for (i in lines.indices) {
            if (CODE_WORD.containsMatchIn(lines[i]) && hasDigitOn(lines, i - 1, i + 1)) return true
        }
        return false
    }

    private fun hasDigitOn(lines: List<String>, first: Int, last: Int): Boolean {
        for (near in maxOf(first, 0)..minOf(last, lines.size - 1)) {
            if (DIGIT.containsMatchIn(lines[near])) return true
        }
        return false
    }
}
