package org.claudevoice.mobile.core

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/** One request as the gate sees it: its path, who sent it, and its headers in any case. */
data class GateRequest(val path: String, val peer: InetAddress, private val headers: Map<String, String>) {
    private val folded = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = folded[name.lowercase()]
}

/** Where a request that passes goes. Read from the same path the gate checks pairing on. */
enum class Route {
    PAIR,
    MCP,
    NOT_FOUND,
}

/** The path as it came, undecoded: only these two exact strings lead anywhere. */
private fun routeOf(path: String) = when (path) {
    Gate.PAIR_PATH -> Route.PAIR
    Gate.MCP_PATH -> Route.MCP
    else -> Route.NOT_FOUND
}

/** What the gate decides: let the request through in [Pass.version] to [Pass.route], or refuse it. */
sealed interface GateDecision {
    data class Pass(val version: Int, val route: Route) : GateDecision

    /**
     * A refusal: its HTTP status, the headers to send, and the body protocol version 1's
     * `error.json` gives it. [code] and [versions] are left out of a version 0 answer.
     */
    data class Refuse(
        val status: Int,
        val error: String,
        val code: String?,
        val headers: Map<String, String>,
        val versions: List<Int>? = null,
    ) : GateDecision
}

/**
 * Whether a request comes from the paired hub's machine. The protocol asks Tailscale for the
 * caller's node; Tailscale on Android offers no such lookup, so the phone pins what pairing saw
 * instead ([PinnedAddress]). Behind this interface, the lookup can replace it when it exists.
 */
fun interface CallerCheck {
    fun isPairedHub(peer: InetAddress): Boolean
}

/** The hub is the machine at the tailnet address it paired from. */
class PinnedAddress(private val hub: InetAddress) : CallerCheck {
    override fun isPairedHub(peer: InetAddress): Boolean = peer == hub
}

/** The paired hub: a hash of its key (the key itself is never kept) and how to know its machine. */
class PairedHub(private val keyHash: ByteArray, private val caller: CallerCheck) {
    fun hasKey(key: String): Boolean = MessageDigest.isEqual(hash(key), keyHash)

    fun isAt(peer: InetAddress): Boolean = caller.isPairedHub(peer)

    companion object {
        fun hash(key: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    }
}

/**
 * The checks every request passes before any MCP is spoken (protocol version 1, "The network",
 * "The version" and "Every later request"): the version both sides speak, the `Host` and
 * `Origin` headers, a caller on the tailnet, and, except for pairing, the paired hub's key from
 * the paired hub's machine. The core gives decisions, never HTTP: the app's listener sends them.
 *
 * [highestSeen] is the highest version the hub has spoken; once it is 1, a request with no
 * version header is refused, so the hub cannot slip back to version 0. [onHigher] hears each rise,
 * so it can be kept with the pairing ([KeptGate]).
 */
class Gate(
    private val own: InetAddress,
    private val port: Int,
    private val machine: String,
    private val hub: PairedHub?,
    highestSeen: Int = 0,
    private val onHigher: ((Int) -> Unit)? = null,
) {
    var highestSeen = highestSeen
        private set

    fun check(request: GateRequest): GateDecision {
        val offered = request.header(VERSION_HEADER)
        val version = if (offered == null) 0 else agreed(offered)
        val slipsBack = version == 0 && highestSeen >= 1
        val refusal = if (version == null || slipsBack) unsupported() else refusal(request, version)
        return refusal ?: GateDecision.Pass(spoke(checkNotNull(version)), routeOf(request.path))
    }

    /** The hub was answered in [version]: remember a rise, and tell whoever keeps it. */
    private fun spoke(version: Int): Int {
        if (version > highestSeen) {
            highestSeen = version
            onHigher?.invoke(version)
        }
        return version
    }

    private fun unsupported(): GateDecision.Refuse {
        val words = "this agent speaks version ${SPOKEN.joinToString()}"
        return refuse(BAD_REQUEST, SPOKEN.max(), words, "unsupported_version", SPOKEN)
    }

    /** The first check the request fails, in the order the protocol lists them, or null. */
    private fun refusal(request: GateRequest, version: Int): GateDecision.Refuse? = when {
        !isOwnHost(request.header("Host")) ->
            refuse(FORBIDDEN, version, "call this phone at its tailnet address and port", "unauthorized")
        request.header("Origin") != null ->
            refuse(FORBIDDEN, version, "a request from a web page is refused", "unauthorized")
        !Tailnet.isTailnetAddress(request.peer) ->
            refuse(FORBIDDEN, version, "call from a machine on the tailnet", "not_on_tailnet")
        request.path == PAIR_PATH -> null
        else -> unauthorised(request)?.let { refuse(UNAUTHORIZED, version, it, "unauthorized").askingForKey() }
    }

    /**
     * Whether [host] is this phone's address and port: `100.64.0.2:8812`, or for IPv6 the
     * address in brackets, `[fd7a:115c:a1e0::2]:8812`, in any form it can be written. Only an
     * address written out is read, so a name is never looked up.
     */
    private fun isOwnHost(host: String?): Boolean {
        val match = host?.let { HOST.matchEntire(it) } ?: return false
        val (v6, v4, givenPort) = match.destructured
        val given = if (own is Inet6Address) v6Bytes(v6) else v4Bytes(v4)
        return givenPort == "$port" && given != null && given.contentEquals(own.address)
    }

    /** Why the request is not the paired hub's, or null when it is. */
    private fun unauthorised(request: GateRequest): String? {
        val pairAgain = "show a new code in the phone app, then on the hub run: claude-voice machines approve $machine"
        val authorization = request.header("Authorization")
        val key = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization }
        return when {
            hub == null -> "this phone is not paired yet; $pairAgain"
            key == null || !hub.hasKey(key) -> "not the paired hub"
            !hub.isAt(request.peer) -> "the hub's address changed; pair again from the hub: $pairAgain"
            else -> null
        }
    }

    private fun GateDecision.Refuse.askingForKey() = copy(headers = headers + ("WWW-Authenticate" to "Bearer"))

    private fun refuse(status: Int, version: Int, error: String, code: String, versions: List<Int>? = null) =
        if (version == 0) {
            GateDecision.Refuse(status, error, code = null, headers = emptyMap())
        } else {
            GateDecision.Refuse(status, error, code, mapOf(VERSION_HEADER to "$version"), versions)
        }

    /** The highest version both speak, or null when they share none or the header is not numbers. */
    private fun agreed(offered: String): Int? {
        val versions = offered.split(",").map { it.trim().toIntOrNull() }
        return if (null in versions) null else versions.filterNotNull().filter { it in SPOKEN }.maxOrNull()
    }

    companion object {
        const val VERSION_HEADER = "Claude-Voice-Protocol"
        const val PAIR_PATH = "/pair"
        const val MCP_PATH = "/mcp"
        private val SPOKEN = listOf(1)
        private val HOST = Regex("""(?:\[([0-9A-Fa-f:]+)]|(\d{1,3}(?:\.\d{1,3}){3})):(\d+)""")
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
    }
}

/** An IPv6 address written out; one with a colon is never looked up as a name. */
private fun v6Bytes(text: String): ByteArray? =
    if (':' !in text) null else runCatching { InetAddress.getByName(text) }.getOrNull()?.address

private fun v4Bytes(text: String): ByteArray? {
    val parts = text.split(".").mapNotNull { it.toIntOrNull()?.takeIf { n -> n <= BYTE_MAX } }
    return if (parts.size == IPV4_PARTS) ByteArray(IPV4_PARTS) { parts[it].toByte() } else null
}

private const val BYTE_MAX = 255
private const val IPV4_PARTS = 4
