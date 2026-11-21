package org.claudevoice.mobile.plugins.sms_send

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.plugins.sms_send.rules.Outbox

/**
 * Where the app builds this plugin (ADR-CVM-007). Only what needs Android is here: whether the
 * owner still lets the app send, asked at each call, and the default SIM that sends. A send
 * Android refuses is logged by its code alone, never with the number or the text.
 */
object Entry {
    fun plugin(context: Context): Plugin {
        val app = context.applicationContext
        val outbox = Outbox.onThePhone(send = { to, text -> send(app, to, text) }, failed = { Log.w(TAG, it) })
        return SmsSendPlugin(outbox) {
            app.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun send(app: Context, to: String, text: String) {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") // the default SIM's manager, before Android 12 offered the service
            SmsManager.getDefault()
        }
        sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
    }

    private const val TAG = "sms_send"
}
