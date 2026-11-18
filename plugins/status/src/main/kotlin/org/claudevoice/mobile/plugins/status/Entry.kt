package org.claudevoice.mobile.plugins.status

import android.content.Context
import org.claudevoice.mobile.pluginapi.Plugin

/**
 * Where the app builds this plugin (ADR-CVM-007). Every plugin module has one, at
 * `org.claudevoice.mobile.plugins.<name>.Entry`, and the app's generated list calls it. It is the
 * plugin's only Android-facing line: it reads from [Context] only what the plugin needs.
 */
object Entry {
    @Suppress("UNUSED_PARAMETER")
    fun plugin(context: Context): Plugin = StatusPlugin()
}
