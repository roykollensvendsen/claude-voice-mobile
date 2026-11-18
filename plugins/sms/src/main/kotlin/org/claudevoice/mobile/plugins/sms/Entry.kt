package org.claudevoice.mobile.plugins.sms

import android.content.Context
import org.claudevoice.mobile.pluginapi.Plugin

/** Where the app builds this plugin (ADR-CVM-007). It reads nothing from [Context] yet. */
object Entry {
    @Suppress("UNUSED_PARAMETER")
    fun plugin(context: Context): Plugin = SmsPlugin()
}
