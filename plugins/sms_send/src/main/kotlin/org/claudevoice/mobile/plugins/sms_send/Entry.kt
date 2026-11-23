package org.claudevoice.mobile.plugins.sms_send

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import org.claudevoice.mobile.capabilities.contacts.Contacts
import org.claudevoice.mobile.capabilities.cursorqueue.SecureChance
import org.claudevoice.mobile.capabilities.smsstore.SmsStore
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.plugins.sms_send.rules.Contact
import org.claudevoice.mobile.plugins.sms_send.rules.Countries
import org.claudevoice.mobile.plugins.sms_send.rules.Journal
import org.claudevoice.mobile.plugins.sms_send.rules.Outbox
import org.claudevoice.mobile.plugins.sms_send.rules.PlainFile
import org.claudevoice.mobile.plugins.sms_send.rules.Recipients
import org.claudevoice.mobile.plugins.sms_send.rules.Reports
import org.claudevoice.mobile.plugins.sms_send.rules.Tracking
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.Locale

/**
 * Where the app builds this plugin (ADR-CVM-007). Only what needs Android is here: whether the
 * owner still lets the app send, asked at each call, the default SIM that sends, and the SIM's
 * country that a contact's number saved without a code is read in. Whether a number given as a
 * number is a contact, or was texted before, is a yes or no from the contacts and the message
 * store, each only with the owner's leave (ADR-CVM-010). How each confirmed text ends is kept in
 * two plain files in the app's no-backup folder, and Android's reports reach them through the
 * [Radio]. A send Android refuses is logged by its code alone, never with the number or the text.
 */
object Entry {
    private var built: Plugin? = null

    /** The plugin, built once per process, so one journal holds the files and one receiver hears the reports. */
    @Synchronized
    fun plugin(context: Context): Plugin {
        val plugin = built ?: build(context.applicationContext)
        built = plugin
        return plugin
    }

    private fun build(app: Context): Plugin {
        val permitted = {
            app.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        }
        val log: (String) -> Unit = { Log.w(TAG, it) }
        val journal = journal(app)
        val reports = journal?.let { Reports(it, log) }
        ReportReceiver.reports = reports
        val radio = Radio(app, reports)
        // With no journal, Tracking holds nothing, so every yes is refused rather than sent unmarked.
        val tracking = Tracking(journal, radio::subscribed, permitted, log)
        val outbox = Outbox.onThePhone(send = radio::send, failed = log, tracking = tracking)
        val telephony = app.getSystemService(TelephonyManager::class.java)
        // The SIM's country, else the phone's region; never the network's, which roams (Countries).
        val country = { Countries.chosen(telephony?.simCountryIso, Locale.getDefault().country) }
        val contacts = Contacts.on(app, country)
        // Asks only while the app already holds READ_SMS, which the `sms` plugin declares; never asked here.
        val store = SmsStore.on(app, country)
        val recipients = Recipients.onThePhone(contacts::has, store::sentBefore) { matches ->
            contacts.numbers(matches)?.map { Contact(it.name, it.number, it.label, it.guessed) }
        }
        return SmsSendPlugin(outbox, recipients, permitted, journal)
    }

    // The journal on disk, or none when a damaged outcome file cannot even be set aside: then the
    // phone refuses each yes, since no text may go without its marker, and the poll says it keeps
    // no outcomes, rather than overwrite the file.
    private fun journal(app: Context): Journal? =
        try {
            val folder = app.noBackupFilesDir
            Journal.open(
                PlainFile(File(folder, "send-outcomes"), FsyncFolder),
                PlainFile(File(folder, "send-markers"), FsyncFolder),
                SecureChance(),
                Clock.systemDefaultZone(),
            )
        } catch (_: IOException) {
            Log.w(TAG, "the phone could not open how its texts ended")
            null
        }

    private const val TAG = "sms_send"
}
