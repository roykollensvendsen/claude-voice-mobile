package org.claudevoice.mobile.capabilities.cursorqueue

import java.security.SecureRandom

/** Where a queue's random parts come from: the real one is [SecureChance]. */
interface Chance {
    /** Eight lowercase letters or digits, chosen anew for each install and each lost key. */
    fun installPart(): String

    /** Twelve letters or digits, for an answer's cursor. */
    fun cursor(): String
}

/** The real [Chance], from the platform's strong random source. */
class SecureChance(private val random: SecureRandom = SecureRandom()) : Chance {
    override fun installPart() = pick(INSTALL_LETTERS, INSTALL_LENGTH)

    override fun cursor() = pick(CURSOR_LETTERS, CURSOR_LENGTH)

    private fun pick(letters: String, length: Int): String {
        val picked = StringBuilder(length)
        while (picked.length < length) picked.append(letters[random.nextInt(letters.length)])
        return picked.toString()
    }

    private companion object {
        const val INSTALL_LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val CURSOR_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val INSTALL_LENGTH = 8
        const val CURSOR_LENGTH = 12
    }
}
