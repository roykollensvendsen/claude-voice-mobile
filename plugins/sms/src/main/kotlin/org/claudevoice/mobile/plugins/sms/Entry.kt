package org.claudevoice.mobile.plugins.sms

import android.content.Context
import org.claudevoice.mobile.pluginapi.Plugin

/**
 * Where the app builds this plugin (ADR-CVM-007). It builds it without its inbox until the
 * receiver comes, so it offers nothing yet.
 */
object Entry {
    @Suppress("UNUSED_PARAMETER")
    fun plugin(context: Context): Plugin = SmsPlugin()
}
