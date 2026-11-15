package org.claudevoice.mobile.core

import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where the phone keeps its paired hub between restarts: a small text the app stores privately. */
interface HubStore {
    fun read(): String?

    /** Replace what is kept, all at once or not at all; an [IOException] when it could not. */
    @Throws(IOException::class)
    fun write(text: String)
}

/**
 * The gate, with its paired hub kept in a [HubStore]: the hash of the hub's key, its name, its
 * tailnet address and the highest protocol version it has spoken. So after a restart the phone
 * still answers its hub alone, and a hub that has spoken version 1 still cannot slip back to 0.
 *
 * Anything kept that cannot be read, or an address off the tailnet, counts as not paired: the
 * phone refuses every call until it is paired again, rather than trust it, and [problem] says so.
 *
 * Requests run on their own threads while the owner may pair again. So the record changes under
 * one lock, and a gate hears a rise only for the pairing it was built from: a request to the old
 * hub still in flight cannot write the old hub back, nor mark the new one as having spoken 1.
 */
class KeptGate(
    private val store: HubStore,
    private val own: InetAddress,
    private val port: Int,
    private val machine: String,
) {
    private val text = store.read()
    private var kept: Kept? = Kept.parse(text)
    /** Which pairing is current: a new token each time, compared by identity. */
    private var pairing = Any()

    /** Why what was kept could not be used, for the app to show; null when it could, or none was. */
    @Volatile
    var problem: String? = if (text != null && kept == null) UNREADABLE else null
        private set

    /** The gate for the hub paired now. Take it again for each request. */
    @Volatile
    var gate: Gate = build()
        private set

    val hubName: String? get() = kept?.name

    /**
     * Pairing gave a new hub: it replaces the one before, whose key stops working at once. False
     * when it could not be written: then nothing changed, and the key must not be sent to the hub.
     */
    @Synchronized
    fun paired(result: PairResult.Paired): Boolean {
        val written = keep(Kept(result.keyHash, result.hubName, result.hubAddress, highest = 0))
        if (written) {
            pairing = Any()
            problem = null
            gate = build()
        }
        return written
    }

    /** Write first, and change what is trusted only once it is written. */
    private fun keep(next: Kept): Boolean {
        try {
            store.write(next.json())
        } catch (_: IOException) {
            return false
        }
        kept = next
        return true
    }

    private fun build(): Gate {
        val hub = kept?.let { PairedHub(it.keyHash, PinnedAddress(it.address)) }
        val builtFor = pairing
        return Gate(own, port, machine, hub, kept?.highest ?: 0) { version -> rose(builtFor, version) }
    }

    /** The hub of pairing [builtFor] spoke a higher version: keep it, unless it has been replaced. */
    @Synchronized
    private fun rose(builtFor: Any, version: Int) {
        val now = kept
        // A rise that cannot be written is kept next time; the request still goes through.
        if (builtFor === pairing && now != null) keep(now.withHighest(version))
    }

    private class Kept(val keyHash: ByteArray, val name: String, val address: InetAddress, val highest: Int) {
        fun withHighest(version: Int) = Kept(keyHash, name, address, version)

        fun json(): String = buildJsonObject {
            put(KEY_HASH, B64.encodeToString(keyHash))
            put(NAME, name)
            put(ADDRESS, B64.encodeToString(address.address))
            put(HIGHEST, highest)
        }.toString()

        companion object {
            fun parse(text: String?): Kept? =
                runCatching { read(Json.parseToJsonElement(checkNotNull(text)).jsonObject) }.getOrNull()

            private fun read(json: JsonObject): Kept? {
                fun field(name: String) = checkNotNull(json[name]).jsonPrimitive
                val hash = Base64.getDecoder().decode(field(KEY_HASH).content)
                val address = InetAddress.getByAddress(Base64.getDecoder().decode(field(ADDRESS).content))
                val name = field(NAME).content
                val highest = field(HIGHEST).int
                val sound = hash.size == HASH_BYTES && Tailnet.isTailnetAddress(address) && name.isNotBlank()
                return if (sound && highest >= 0) Kept(hash, name, address, highest) else null
            }
        }
    }

    private companion object {
        const val UNREADABLE = "the pairing kept on this phone could not be read; pair it again from the hub"
        const val KEY_HASH = "key_hash"
        const val NAME = "name"
        const val ADDRESS = "address"
        const val HIGHEST = "highest"
        const val HASH_BYTES = 32
        val B64: Base64.Encoder = Base64.getEncoder()
    }
}
