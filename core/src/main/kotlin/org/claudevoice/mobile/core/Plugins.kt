package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin

/**
 * The plugins the app was built with, as the core offers them to the hub. Each tool is named
 * `<plugin>_<tool>`, the way the hub already names a plugin's tools, so no two can collide.
 */
class Plugins(private val plugins: List<Plugin>) {
    init {
        val seen = mutableSetOf<String>()
        for (plugin in plugins) {
            for (tool in plugin.tools) {
                require(tool.name.startsWith("${plugin.name}_")) { "${plugin.name} offers ${tool.name}, not named after it" }
                require(seen.add(tool.name)) { "two plugins offer ${tool.name}" }
                val taken = (tool.input["properties"] as? JsonObject)?.keys.orEmpty() intersect PHONE_ONLY
                require(taken.isEmpty()) { "${tool.name} takes $taken; only the phone's own screens change that" }
            }
        }
    }

    fun offered(): List<String> = plugins.flatMap { plugin -> plugin.tools.map { it.name } }
}

/** The settings only the phone changes, by their names on the wire. No tool may take one. */
private val PHONE_ONLY = setOf("microphone", "location_log", "direct_calls", "camera")
