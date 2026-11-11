package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
                val taken = inputNames(tool.input).filter { it.fold() in PHONE_ONLY }
                require(taken.isEmpty()) { "${tool.name} takes $taken; only the phone's own screens change that" }
            }
        }
    }

    fun offered(): List<String> = plugins.flatMap { plugin -> plugin.tools.map { it.name } }
}

/**
 * The settings only the phone changes, folded to lowercase letters. No tool may take one.
 *
 * This is a tripwire for a plain mistake, not the wall. The wall is the type: a plugin is handed
 * a read-only `PhoneSettings` and cannot reach `SettingsKeeper`, so a tool has nothing to write
 * them with whatever its input is called.
 */
private val PHONE_ONLY = setOf("microphone", "locationlog", "directcalls", "camera")

private fun String.fold() = lowercase().filter { it.isLetter() }

/** Every property name in a JSON schema, at any depth. */
private fun inputNames(schema: JsonElement): List<String> =
    when (schema) {
        is JsonObject -> {
            val here = (schema["properties"] as? JsonObject)?.keys.orEmpty().toList()
            here + schema.values.flatMap { inputNames(it) }
        }
        is JsonArray -> schema.flatMap { inputNames(it) }
        else -> emptyList()
    }
