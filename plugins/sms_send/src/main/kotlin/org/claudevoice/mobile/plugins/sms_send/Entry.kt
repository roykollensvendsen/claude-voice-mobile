package org.claudevoice.mobile.plugins.sms_send

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import org.claudevoice.mobile.capabilities.contacts.Contacts
import org.claudevoice.mobile.capabilities.smsstore.SmsStore
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.plugins.sms_send.rules.Contact
import org.claudevoice.mobile.plugins.sms_send.rules.Countries
import org.claudevoice.mobile.plugins.sms_send.rules.Outbox
import org.claudevoice.mobile.plugins.sms_send.rules.Recipients
import java.util.Locale

/**
 * Where the app builds this plugin (ADR-CVM-007). Only what needs Android is here: whether the
 * owner still lets the app send, asked at each call, the default SIM that sends, and the SIM's
 * country that a contact's number saved without a code is read in. Whether a number given as a
 * number is a contact, or was texted before, is a yes or no from the contacts and the message
 * store, each only with the owner's leave (ADR-CVM-010). A send
 * Android refuses is logged by its code alone, never with the number or the text.
 */
object Entry {
    fun plugin(context: Context): Plugin {
        val app = context.applicationContext
        val outbox = Outbox.onThePhone(send = { (to, text) -> send(app, to, text) }, failed = { Log.w(TAG, it) })
        val telephony = app.getSystemService(TelephonyManager::class.java)
        // The SIM's country, else the phone's region; never the network's, which roams (Countries).
        val country = { Countries.chosen(telephony?.simCountryIso, Locale.getDefault().country) }
        val contacts = Contacts.on(app, country)
        // Asks only while the app already holds READ_SMS, which the `sms` plugin declares; never asked here.
        val store = SmsStore.on(app, country)
        val recipients = Recipients.onThePhone(contacts::has, store::sentBefore) { matches ->
            contacts.numbers(matches)?.map { Contact(it.name, it.number, it.label, it.guessed) }
        }
        return SmsSendPlugin(outbox, recipients) {
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
