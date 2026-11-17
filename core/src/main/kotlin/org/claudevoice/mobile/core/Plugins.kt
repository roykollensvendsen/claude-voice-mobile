package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            checkReach(plugin)
            for (tool in plugin.tools) {
                check(plugin, tool)
                require(owners.put(tool.name, plugin) == null) { "two plugins offer ${tool.name}" }
            }
        }
        this.owners = owners
    }

    /** Each plugin's entry in `agent_info`, as protocol version 1's `plugin.json` gives it. */
    fun entries(): JsonArray = JsonArray(plugins.map { entry(it) })

    /** The first plugin that now says it uses a name the protocol does not know, or null when none does. */
    fun unknownUse(): String? = plugins.firstOrNull { !usesKnown(it.uses) }?.name

    fun offered(): List<String> = tools().map { it.name }

    /** Every tool, in the order the plugins and their tools were given. */
    fun tools(): List<Tool> = plugins.flatMap { it.tools }

    /** The plugin that offers [tool], or null when none does. */
    fun owner(tool: String): Plugin? = owners[tool]

    private fun check(plugin: Plugin, tool: Tool) {
        val own = tool.name.removePrefix("${plugin.name}_")
        require(own != tool.name) { "${plugin.name} offers ${tool.name}, not named after it" }
        require(tool.name != AgentInfo.TOOL) { "${plugin.name} offers ${tool.name}, which the core answers" }
        require(TOOL_PART.matches(own)) { "the tool name '${tool.name}' breaks the protocol's rule" }
        val taken = inputNames(tool.input).filter { it.fold() in PHONE_ONLY }
        require(taken.isEmpty()) { "${tool.name} takes $taken; only the phone's own screens change that" }
    }

    /** What it uses is named as the protocol names things, once each; it confirms only with its own tools. */
    private fun checkReach(plugin: Plugin) {
        val uses = plugin.uses
        require(usesKnown(uses)) { "${plugin.name} uses $uses" }
        val own = plugin.tools.map { it.name }
        val confirms = plugin.confirms
        require(own.containsAll(confirms) && confirms.toSet().size == confirms.size) {
            "${plugin.name} confirms $confirms, not all among its own tools once"
        }
    }

    internal companion object {
        /** As agent-protocol names a plugin: lowercase letters, digits and underscores, from a letter, at most 32. */
        val PLUGIN_NAME = Regex("[a-z][a-z0-9_]{0,31}")
        val TOOL_PART = Regex("[a-z0-9_]+")

        /**
         * What a plugin in this app may say it uses: the nine names protocol version 1 gives as
         * examples. This is stricter than v1, whose `plugin.json` only sets a pattern and lets a
         * hub show a name it does not know as unknown. It is this app's own rule: its plugins are
         * built for v1, so a name outside the nine can only be a typo. A later version's names
         * are added here when the app moves to it.
         */
        val USES = setOf(
            "microphone", "camera", "location", "calls", "contacts", "notifications", "media", "sensors", "usage",
        )

        /** Every name known, and none twice. */
        fun usesKnown(uses: List<String>) = USES.containsAll(uses) && uses.toSet().size == uses.size
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

private fun strings(names: List<String>) = JsonArray(names.map { JsonPrimitive(it) })

/**
 * One plugin as `agent_info` reports it, with what it uses read now and checked again: a plugin
 * may change it while running. A name the hub cannot know fails the answer loudly, never sent.
 */
private fun entry(plugin: Plugin): JsonObject {
    val uses = plugin.uses
    check(Plugins.usesKnown(uses)) { "${plugin.name} now uses $uses" }
    return JsonObject(
        mapOf(
            "name" to JsonPrimitive(plugin.name),
            "tools" to strings(plugin.tools.map { it.name }),
            "confirms" to strings(plugin.confirms),
            "uses" to strings(uses),
        ),
    )
}
