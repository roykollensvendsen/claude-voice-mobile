package org.claudevoice.mobile.core

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

    fun write(text: String)
}

/**
 * The gate, with its paired hub kept in a [HubStore]: the hash of the hub's key, its name, its
 * tailnet address and the highest protocol version it has spoken. So after a restart the phone
 * still answers its hub alone, and a hub that has spoken version 1 still cannot slip back to 0.
 *
 * Anything kept that cannot be read, or an address off the tailnet, counts as not paired: the
 * phone refuses every call until it is paired again, rather than trust it.
 */
class KeptGate(
    private val store: HubStore,
    private val own: InetAddress,
    private val port: Int,
    private val machine: String,
) {
    private var kept: Kept? = Kept.parse(store.read())

    var gate: Gate = build()
        private set

    val hubName: String? get() = kept?.name

    /** Pairing gave a new hub: it replaces the one before, whose key stops working at once. */
    fun paired(result: PairResult.Paired) {
        keep(Kept(result.keyHash, result.hubName, result.hubAddress, highest = 0))
        gate = build()
    }

    private fun keep(next: Kept) {
        kept = next
        store.write(next.json())
    }

    private fun build(): Gate {
        val hub = kept?.let { PairedHub(it.keyHash, PinnedAddress(it.address)) }
        return Gate(own, port, machine, hub, kept?.highest ?: 0, ::rose)
    }

    /** The hub spoke a higher version: keep it, so a restart cannot let it slip back. */
    private fun rose(version: Int) {
        kept?.let { keep(it.withHighest(version)) }
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
        const val KEY_HASH = "key_hash"
        const val NAME = "name"
        const val ADDRESS = "address"
        const val HIGHEST = "highest"
        const val HASH_BYTES = 32
        val B64: Base64.Encoder = Base64.getEncoder()
    }
}
