package org.claudevoice.mobile.core

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
            }
        }
    }

    fun offered(): List<String> = plugins.flatMap { plugin -> plugin.tools.map { it.name } }
}
