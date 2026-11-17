package org.claudevoice.mobile.core

import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place the agent's parts are built and joined: the pairing, the MCP handler with what
 * the phone offers, the gate kept in [store], the HTTP server, and the listener that follows the
 * tailnet. The app gives it [log], the same log its screen reads, so a call the hub makes is the
 * call the owner sees.
 *
 * What remembers stays for the agent's life, whatever address the phone has: the pairing's
 * code and wrong tries, and the call ids the MCP handler keeps. So a hub that retries a call
 * after the tailnet dropped and came back is still told it was a duplicate, never run twice.
 * Only the gate is built again for each address, from [store], since it checks the address the
 * hub sent to.
 */
class PhoneAgent internal constructor(
    private val store: HubStore,
    val log: CallLog,
    private val nameOf: (InetAddress) -> String?,
    describe: (AgentPlace) -> AgentInfo,
    private val parts: Parts,
) {
    /**
     * [describe] builds what the phone offers, given where it is: the app's plugins, version,
     * machine name and settings, as `{ place -> AgentInfo(plugins, version, machine, settings, place) }`.
     */
    constructor(store: HubStore, log: CallLog, nameOf: (InetAddress) -> String?, describe: (AgentPlace) -> AgentInfo) :
        this(store, log, nameOf, describe, Parts())

    /** What tests change: the pairing, the port, the call limit and how a server is opened. */
    internal class Parts(
        val pairing: Pairing = Pairing.forPhone(),
        val port: Int = PORT,
        val callLimit: Duration = McpHandler.CALL_LIMIT,
        val serve: (Handler, (InetAddress) -> Boolean, BindAddress, Int) -> Server = serving(),
    )

    val pairing: Pairing get() = parts.pairing

    /** Where the phone listens now and its gate, set as a server opens and cleared as it stops. */
    private class Here(val address: InetAddress, val gate: KeptGate)

    private val here = AtomicReference<Here?>()

    private val place = object : AgentPlace {
        // Empty only while the phone listens nowhere, when no server is open to answer agent_info.
        override fun address(): String = here.get()?.address?.hostAddress.orEmpty()

        override fun hub(): String? = here.get()?.gate?.hubName
    }

    private val info = describe(place)

    private val mcp = McpHandler(info, callLimit = parts.callLimit, log = log)

    private val listening = Listening(parts.port) { address, at ->
        val gate = KeptGate(store, own = address.address, port = at, machine = info.machine)
        val endpoint = HubEndpoint(gate, parts.pairing, nameOf, mcp = mcp)
        val now = Here(address.address, gate)
        // Set before the server opens, so its first request already knows where it is.
        here.set(now)
        // A bind that fails leaves nothing behind either.
        val server = runCatching { parts.serve(endpoint, endpoint::admits, address, at) }
            .onFailure { here.compareAndSet(now, null) }
            .getOrThrow()
        Server {
            server.stop()
            // Listening nowhere now: nothing reports the old address or its gate.
            here.compareAndSet(now, null)
        }
    }

    /** Whether the phone listens for the hub now, and where; or why not. */
    val status: ListenStatus get() = listening.status

    /** Why the kept pairing could not be used, for the owner; null when it could, or there is none. */
    val problem: String? get() = here.get()?.gate?.problem

    /** The tailnet as the phone sees it now: listen, move to the new address, or stop. */
    fun follow(state: TailnetState) = listening.follow(state)

    /** Stop for good, as when the app's service ends. */
    fun stop() = listening.stop()

    companion object {
        /** Protocol version 1's port, unless the hub's settings name another. */
        const val PORT = 8812

        /** The real HTTP server; [bind] is for tests, to listen where a test machine can. */
        internal fun serving(bind: ((InetAddress, Int) -> ServerSocket)? = null) =
            { handler: Handler, admit: (InetAddress) -> Boolean, address: BindAddress, port: Int ->
                val clock = System::nanoTime
                val exchange = HttpExchange(RequestReader(clock), handler = handler)
                HttpServerOpener(exchange, clock, admit, bind).open(address, port)
            }
    }
}
