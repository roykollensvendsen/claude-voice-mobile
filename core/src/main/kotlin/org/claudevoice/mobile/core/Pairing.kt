package org.claudevoice.mobile.core

import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.security.SecureRandom

/** What pairing gives: the hub's new key, or a refusal with protocol version 1's `error.json`. */
sealed interface PairResult {
    /**
     * The key to send the hub once, and what the phone keeps: a hash of it, the hub's name and
     * its tailnet address. The key is left out of [toString], so a log never shows it.
     */
    class Paired(val key: String, val keyHash: ByteArray, val hubName: String, val hubAddress: InetAddress) :
        PairResult {
        override fun toString() = "Paired(hub=$hubName at ${hubAddress.hostAddress})"
    }

    data class Refused(val status: Int, val error: String, val code: String) : PairResult
}

/**
 * Pairing, as protocol version 1 sets it: the phone's owner asks for a code, which works once for
 * ten minutes; a new code ends the old one, and five wrong codes end the pairing. The right code
 * from a machine on the tailnet gives a new random key of 32 bytes.
 *
 * The protocol asks Tailscale who is calling. Tailscale on Android cannot say, so the app passes
 * [pair] a lookup of the caller's name (its MagicDNS name), and the phone pins the caller's
 * address (`decisions/deferred.md`).
 *
 * The random source is a [SecureRandom] by type, with no default, so no caller can hand in a
 * predictable one; [forPhone] is what the app uses. Requests may come at the same moment, so the
 * code, its time and the wrong-count change under one lock.
 */
class Pairing(private val clock: () -> Instant, internal val random: SecureRandom) {
    private var code: String? = null
    private var until: Instant = Instant.MIN
    private var wrong = 0

    /** A new code to show the owner. Any code before it stops working. */
    @Synchronized
    fun newCode(): String {
        val made = String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
        code = made
        until = clock() + LIFETIME
        wrong = 0
        return made
    }

    /** Whether a code is shown and still works: then a machine other than the hub may pair. */
    @Synchronized
    fun isOpen(): Boolean = open()

    /** Under the lock: a code is shown, and its ten minutes have not run out. */
    private fun open() = code != null && clock() < until

    /**
     * The hub's `POST /pair`, from [caller], whose tailnet name [nameOf] looks up. The code is
     * read as typed, trimmed and in either case. The lookup runs outside the lock, and only
     * while a code is open, so no one on the tailnet can make the phone look names up at will.
     */
    fun pair(offered: String, caller: InetAddress, nameOf: (InetAddress) -> String?): PairResult = when {
        !Tailnet.isTailnetAddress(caller) -> PairResult.Refused(FORBIDDEN, OFF_TAILNET, "not_on_tailnet")
        !isOpen() -> wrongCode()
        else -> named(offered, caller, nameOf(caller))
    }

    /** A caller with no name counts as a wrong try, so a nameless machine cannot try forever. */
    private fun named(offered: String, caller: InetAddress, name: String?): PairResult =
        if (name == null) {
            countWrong()
            PairResult.Refused(FORBIDDEN, NO_NAME, "not_on_tailnet")
        } else {
            tryCode(offered.trim().uppercase(), name, caller)
        }

    @Synchronized
    private fun tryCode(offered: String, name: String, caller: InetAddress): PairResult {
        val shown = code
        return when {
            // Checked again under the lock: the code may have ended since pair() asked.
            shown == null || !open() -> wrongCode()
            !MessageDigest.isEqual(offered.toByteArray(), shown.toByteArray()) -> wrongCode().also { tooMany() }
            else -> paired(name, caller)
        }
    }

    @Synchronized
    private fun countWrong() = tooMany()

    private fun tooMany() {
        wrong += 1
        if (wrong >= MAX_WRONG) code = null
    }

    private fun wrongCode() =
        PairResult.Refused(FORBIDDEN, "wrong or late code; show a new one in the phone app", "wrong_code")

    private fun paired(name: String, caller: InetAddress): PairResult.Paired {
        code = null
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return PairResult.Paired(key, hash, name, caller)
    }

    companion object {
        /** The pairing the app uses: the real clock, and the platform's strong random source. */
        fun forPhone() = Pairing(clock = Instant::now, random = SecureRandom())

        /** Letters and digits that neither look nor sound like one another. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val OFF_TAILNET =
            "pair from a machine on the tailnet: turn on Tailscale on the hub, and use the phone's tailnet address"
        private const val NO_NAME = "the phone cannot find this machine's tailnet name; turn on MagicDNS " +
            "in the Tailscale admin console, under DNS (login.tailscale.com/admin/dns), then pair again"
        private const val CODE_LENGTH = 8
        private const val KEY_BYTES = 32
        private const val MAX_WRONG = 5
        private const val FORBIDDEN = 403
        private val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
