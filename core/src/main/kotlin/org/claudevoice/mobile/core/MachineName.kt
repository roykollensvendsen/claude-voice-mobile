package org.claudevoice.mobile.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What came of the owner's confirming a name. */
enum class Confirmed {
    /** Kept: the phone is this machine from now on. */
    Kept,

    /** Not a name the protocol takes: lowercase letters, digits and hyphens, from a letter, at most 32. */
    NotAllowed,

    /** The phone is paired under another name; changing it means pairing again. */
    PairedAs,

    /** The phone could not write it; nothing changed. */
    NotWritten,
}

/**
 * The name the phone gives itself as a machine of the tailnet, confirmed by the owner before
 * the first pairing, since two phones of one model would get the same suggestion. Kept in
 * [store] as `{"version":1,"name":"…"}`. It is a setting of the phone alone: only the owner's
 * screen confirms it, and no tool the app offers reaches it.
 *
 * It is the identity the hub approved. So while [paired] says a hub is paired, a different name
 * is refused here, in the core, whatever screen asks.
 */
class MachineName(private val store: TextStore, private val paired: () -> Boolean) {
    @Volatile
    var name: String? = parse(store.read())
        private set

    @Synchronized
    fun confirm(given: String): Confirmed = when {
        !AgentInfo.MACHINE.matches(given) -> Confirmed.NotAllowed
        given == name -> Confirmed.Kept
        paired() -> Confirmed.PairedAs
        else -> kept(given)
    }

    private fun kept(given: String): Confirmed = try {
        store.write(JsonObject(mapOf("version" to JsonPrimitive(VERSION), "name" to JsonPrimitive(given))).toString())
        name = given
        Confirmed.Kept
    } catch (_: IOException) {
        Confirmed.NotWritten
    }

    private companion object {
        const val VERSION = 1

        /** The kept name, or null when there is none, it cannot be read, or the protocol would not take it. */
        fun parse(text: String?): String? {
            val kept = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val version = kept?.get("version")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            val name = kept?.get("name")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            return name?.takeIf { version == VERSION && AgentInfo.MACHINE.matches(it) }
        }
    }
}

/**
 * What is built once a name is confirmed, with that name: null until [name] gives one, then the
 * same one for good. So the agent is never built under a name the owner did not choose.
 */
class OnceNamed<T : Any>(private val name: () -> String?, private val build: (String) -> T) {
    private var built: T? = null

    @Synchronized
    fun get(): T? = built ?: name()?.let(build)?.also { built = it }
}
