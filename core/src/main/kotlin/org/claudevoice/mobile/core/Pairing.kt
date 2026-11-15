package org.claudevoice.mobile.core

import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

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
 */
class Pairing(private val clock: () -> Instant, private val random: Random) {
    private var code: String? = null
    private var until: Instant = Instant.MIN
    private var wrong = 0

    /** A new code to show the owner. Any code before it stops working. */
    fun newCode(): String {
        val made = String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
        code = made
        until = clock() + LIFETIME
        wrong = 0
        return made
    }

    /** The hub's `POST /pair`, from [caller], whose tailnet name [nameOf] looks up. */
    fun pair(offered: String, caller: InetAddress, nameOf: (InetAddress) -> String?): PairResult {
        val onTailnet = Tailnet.isTailnetAddress(caller)
        val name = if (onTailnet) nameOf(caller) else null
        val shown = code
        return when {
            !onTailnet -> PairResult.Refused(FORBIDDEN, "pair from a machine on the tailnet", "not_on_tailnet")
            name == null -> PairResult.Refused(FORBIDDEN, NO_NAME, "not_on_tailnet")
            shown == null || clock() >= until -> wrongCode()
            !MessageDigest.isEqual(offered.toByteArray(), shown.toByteArray()) -> wrongCode().also { tooMany() }
            else -> paired(name, caller)
        }
    }

    private fun tooMany() {
        wrong += 1
        if (wrong >= MAX_WRONG) code = null
    }

    private fun wrongCode() =
        PairResult.Refused(FORBIDDEN, "wrong or late code; show a new one in the phone app", "wrong_code")

    private fun paired(name: String, caller: InetAddress): PairResult.Paired {
        code = null
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(KEY_BYTES))
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return PairResult.Paired(key, hash, name, caller)
    }

    companion object {
        /** Letters and digits that neither look nor sound like one another. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val NO_NAME = "the phone cannot find this machine's tailnet name; turn on MagicDNS " +
            "in the Tailscale admin console, under DNS (login.tailscale.com/admin/dns), then pair again"
        private const val CODE_LENGTH = 8
        private const val KEY_BYTES = 32
        private const val MAX_WRONG = 5
        private const val FORBIDDEN = 403
        private val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
