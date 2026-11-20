package org.claudevoice.mobile.plugins.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.claudevoice.mobile.pluginapi.Plugin

/**
 * Where the app builds this plugin (ADR-CVM-007), with the app's one inbox, which the receiver
 * fills. Whether the owner still lets the app receive and read messages is asked at each call.
 */
object Entry {
    fun plugin(context: Context): Plugin {
        val app = context.applicationContext
        return SmsPlugin(SmsInbox.get(app)) { permitted(app) }
    }

    private fun permitted(app: Context) =
        NEEDED.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private val NEEDED = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
}
