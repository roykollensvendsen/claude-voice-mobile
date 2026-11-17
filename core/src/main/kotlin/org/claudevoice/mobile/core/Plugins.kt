package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool

/**
 * The plugins the app was built with, as the core offers them to the hub. Each tool is named
 * `<plugin>_<tool>`, the way the hub already names a plugin's tools, so no two can collide.
 * A name that breaks the protocol's rule, or a second plugin or tool of one name, stops the app
 * at start-up, so a call can never reach the wrong plugin.
 */
class Plugins(private val plugins: List<Plugin>) {
    private val owners: Map<String, Plugin>

    init {
        val names = mutableSetOf<String>()
        val owners = mutableMapOf<String, Plugin>()
        for (plugin in plugins) {
            require(PLUGIN_NAME.matches(plugin.name)) { "the plugin name '${plugin.name}' breaks the protocol's rule" }
            require(names.add(plugin.name)) { "two plugins are named ${plugin.name}" }
            for (tool in plugin.tools) {
                check(plugin, tool)
                require(owners.put(tool.name, plugin) == null) { "two plugins offer ${tool.name}" }
            }
        }
        this.owners = owners
    }

    fun offered(): List<String> = tools().map { it.name }

    /** Every tool, in the order the plugins and their tools were given. */
    fun tools(): List<Tool> = plugins.flatMap { it.tools }

    /** The plugin that offers [tool], or null when none does. */
    fun owner(tool: String): Plugin? = owners[tool]

    private fun check(plugin: Plugin, tool: Tool) {
        val own = tool.name.removePrefix("${plugin.name}_")
        require(own != tool.name) { "${plugin.name} offers ${tool.name}, not named after it" }
        require(TOOL_PART.matches(own)) { "the tool name '${tool.name}' breaks the protocol's rule" }
        val taken = inputNames(tool.input).filter { it.fold() in PHONE_ONLY }
        require(taken.isEmpty()) { "${tool.name} takes $taken; only the phone's own screens change that" }
    }

    private companion object {
        /** As agent-protocol names a plugin: lowercase letters, digits and underscores, from a letter, at most 32. */
        val PLUGIN_NAME = Regex("[a-z][a-z0-9_]{0,31}")
        val TOOL_PART = Regex("[a-z0-9_]+")
    }
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
