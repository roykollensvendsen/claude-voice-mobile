package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.claudevoice.mobile.pluginapi.PhoneSettings

/** Where the phone is on the tailnet now, and its hub: both change while the app runs. */
interface AgentPlace {
    /** The tailnet address the phone listens on. */
    fun address(): String

    /** The paired hub's name, or null before pairing. */
    fun hub(): String?
}

/**
 * The answer of `agent_info`, the tool every agent offers, as protocol version 1's
 * `agent-info.json` gives it: what the phone is, which tools and plugins it offers, what each
 * plugin uses, and its privacy settings. Everything is read when the hub asks, so a setting
 * changed on the phone shows at once.
 */
class AgentInfo(
    val plugins: Plugins,
    val version: String,
    private val machine: String,
    private val settings: PhoneSettings,
    private val place: AgentPlace,
) {
    init {
        require(MACHINE.matches(machine)) { "the machine name '$machine' breaks the protocol's rule" }
    }

    /** The paired hub's name now, or null before pairing. */
    fun hub(): String? = place.hub()

    fun report(): JsonObject = report(plugins.usesNow())

    /** The answer, with what each plugin uses as [uses] read it. */
    fun report(uses: List<List<String>>): JsonObject = JsonObject(
        mapOf(
            "protocol" to JsonPrimitive(PROTOCOL),
            "machine" to JsonPrimitive(machine),
            "address" to JsonPrimitive(place.address()),
            "hub" to JsonPrimitive(place.hub()),
            "tools" to JsonArray(plugins.offered().map { JsonPrimitive(it) }),
            "version" to JsonPrimitive(version),
            "plugins" to plugins.entries(uses),
            "privacy" to privacyReport(settings),
        ),
    )

    companion object {
        /** The protocol version this answer is in. */
        const val PROTOCOL = 1

        /** The tool's own name; no plugin may take it. */
        const val TOOL = "agent_info"

        /** As agent-protocol names a machine: lowercase letters, digits and hyphens, from a letter, at most 32. */
        private val MACHINE = Regex("[a-z][a-z0-9-]{0,31}")
    }
}
