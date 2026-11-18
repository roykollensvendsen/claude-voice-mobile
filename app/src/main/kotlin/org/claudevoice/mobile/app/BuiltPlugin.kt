package org.claudevoice.mobile.app

import org.claudevoice.mobile.pluginapi.Plugin

/** ADR-CVM-007: a plugin's folder, Gradle path, package and protocol name are one string. */
internal object BuiltPlugin {
    /** [plugin], built from `plugins/[folder]`; refused when it calls itself anything else. */
    fun named(folder: String, plugin: Plugin): Plugin {
        check(plugin.name == folder) {
            "the plugin in plugins/$folder calls itself ${plugin.name}; its folder, package and name are one string"
        }
        return plugin
    }
}
