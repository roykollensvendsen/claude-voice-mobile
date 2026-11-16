package org.claudevoice.mobile.core

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the hub reaches on the phone, behind the gate: `/pair`, as protocol version 1 sets it,
 * and `/mcp`, handed to [mcp]. Every answer that passes the gate names the version it answers
 * in. [admits] is who the server lets in at all, as ADR-CVM-005 sets: before pairing, or while
 * a pairing code is open, any tailnet address; otherwise only the paired hub's.
 *
 * [nameOf] looks up a caller's tailnet name for pairing. It is given [lookupLimit] at most, so
 * a stalled lookup cannot hold one of the server's few connections; past it, the caller has no
 * name, and pairing says how to give it one.
 */
class HubEndpoint(
    private val kept: KeptGate,
    private val pairing: Pairing,
    private val nameOf: (InetAddress) -> String?,
    private val lookupLimit: Duration = LOOKUP_LIMIT,
    private val mcp: Handler,
) : Handler {
    /** For the server's admit: cheap, on the accept thread, with no lookup. Tailnet callers only. */
    fun admits(peer: InetAddress): Boolean {
        val hub = kept.hubAddress
        return Tailnet.isTailnetAddress(peer) && (hub == null || hub == peer || pairing.isOpen())
    }

    /**
     * The caller's name, or null when the lookup fails or takes longer than [lookupLimit]. It
     * runs on [LOOKUPS], two daemon threads of the endpoint's own, so a stalled lookup never
     * holds a thread the rest of the app shares. Past the limit it is cancelled and interrupted.
     * An interrupt of the connection's own thread also ends the wait as no name, and the thread
     * keeps its interrupt flag, so the server closes the connection as it would anyway.
     */
    private fun nameWithin(peer: InetAddress): String? {
        val lookup = LOOKUPS.submit<String?> { nameOf(peer) }
        return try {
            lookup.get(lookupLimit.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            lookup.cancel(true)
        }
    }

    override fun handle(request: ReadResult.Request, peer: InetAddress): Answer =
        when (val decision = kept.gate.check(GateRequest(request.target, peer, request.headers()))) {
            is GateDecision.Refuse -> refused(decision)
            is GateDecision.Pass -> versioned(decision.version, routed(decision.route, request, peer))
        }

    private fun routed(route: Route, request: ReadResult.Request, peer: InetAddress): Answer = when (route) {
        Route.PAIR -> pair(codeIn(request.body), peer)
        else -> mcp.handle(request, peer)
    }

    private fun pair(offered: String, peer: InetAddress): Answer =
        when (val result = pairing.pair(offered, peer, ::nameWithin)) {
            is PairResult.Refused -> error(result.status, result.error, result.code)
            is PairResult.Paired -> if (kept.paired(result)) given(result) else error(INTERNAL_ERROR, NOT_KEPT, null)
        }

    /** The key, once, and the hub's name: sent only after the pairing is kept. */
    private fun given(result: PairResult.Paired) = Answer(
        OK,
        buildJsonObject {
            put("key", JsonPrimitive(result.key))
            put("hub", JsonPrimitive(result.hubName))
        }.bytes(),
    )

    private fun versioned(version: Int, answer: Answer): Answer =
        if (version < 1) answer else Answer(answer.status, answer.body, answer.headers + (PROTOCOL to "$version"))

    private fun refused(refuse: GateDecision.Refuse) =
        error(refuse.status, refuse.error, refuse.code, refuse.versions, refuse.headers)

    private companion object {
        val LOOKUP_LIMIT: Duration = Duration.ofSeconds(2)
        const val LOOKUP_THREADS = 2
        val LOOKUPS: ExecutorService = Executors.newFixedThreadPool(LOOKUP_THREADS) { task ->
            Thread(task, "name-lookup").apply { isDaemon = true }
        }
        const val OK = 200
        const val INTERNAL_ERROR = 500
        const val PROTOCOL = "Claude-Voice-Protocol"
        const val NOT_KEPT = "the phone could not keep the pairing; free some space on the phone and pair again"

        /** The code in `{"code": "…"}`; anything else is an empty code, which is wrong. */
        fun codeIn(body: ByteArray): String =
            runCatching { (Json.parseToJsonElement(String(body, Charsets.UTF_8)) as JsonObject)["code"]?.jsonPrimitive }
                .getOrNull()?.takeIf { it.isString }?.contentOrNull ?: ""

        /** Protocol version 1's `error.json`, with [code] and [versions] when there are any. */
        fun error(
            status: Int,
            error: String,
            code: String?,
            versions: List<Int>? = null,
            headers: Map<String, String> = emptyMap(),
        ) = Answer(
            status,
            buildJsonObject {
                put("error", JsonPrimitive(error))
                code?.let { put("code", JsonPrimitive(it)) }
                versions?.let { put("versions", JsonArray(it.map(::JsonPrimitive))) }
            }.bytes(),
            headers,
        )

        fun JsonObject.bytes() = toString().toByteArray()
    }
}
