package org.claudevoice.mobile.plugins.sms_send

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import org.claudevoice.mobile.plugins.sms_send.rules.Handover
import org.claudevoice.mobile.plugins.sms_send.rules.Reports

/**
 * The phone's radio for `sms_send` (ADR-CVM-010, "Delivered"). It hands each recipient's text to
 * Android's default text subscription as the parts `divideMessage` gives, with a sent and a
 * delivered report asked for each part, and hands those reports to [reports]. Each report's
 * intent is explicit to this app, immutable, and told apart by its own address, so no other app
 * can send one and no stale one is reused. Only a GSM delivery report is read; any other gives no
 * outcome, since its status is coded otherwise.
 */
internal class Radio(private val app: Context, private val reports: Reports?) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reports = reports ?: return
            val token = intent.getStringExtra(TOKEN) ?: return
            val position = intent.getIntExtra(POSITION, 0)
            val part = intent.getIntExtra(PART, -1)
            when (intent.action) {
                sent -> reports.sent(token, position, part, resultCode)
                delivered -> reports.delivered(token, position, part, status(intent))
            }
        }
    }

    private val sent = "${app.packageName}.sms_send.SENT"
    private val delivered = "${app.packageName}.sms_send.DELIVERED"

    /** Start hearing the reports; only this app's own intents reach the receiver. */
    fun listen() {
        val filter = IntentFilter().apply {
            addAction(sent)
            addAction(delivered)
            addDataScheme(SCHEME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") // before Android 13 there is no flag; the intents are explicit
            app.registerReceiver(receiver, filter)
        }
    }

    /** Whether the phone has a default subscription to send texts with. */
    fun subscribed(): Boolean =
        SubscriptionManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /** Hand [handover] to Android; throws as SmsManager does, a SecurityException once sending was taken back. */
    fun send(handover: Handover) {
        val sms = manager()
        val parts = sms.divideMessage(handover.text)
        reports?.expect(handover.token, handover.position, parts.size)
        val sentReports = ArrayList(parts.indices.map { report(sent, handover, it) })
        val deliveredReports = ArrayList(parts.indices.map { report(delivered, handover, it) })
        sms.sendMultipartTextMessage(handover.number, null, parts, sentReports, deliveredReports)
    }

    private fun report(action: String, handover: Handover, part: Int): PendingIntent {
        val address = Uri.fromParts(SCHEME, "${handover.token}/${handover.position}/$part", null)
        val intent = Intent(action, address)
            .setPackage(app.packageName)
            .putExtra(TOKEN, handover.token)
            .putExtra(POSITION, handover.position)
            .putExtra(PART, part)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }

    private fun manager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") // the default SIM's manager, before Android 12 offered the service
            SmsManager.getDefault()
        }

    private companion object {
        const val SCHEME = "sms-send"
        const val TOKEN = "token"
        const val POSITION = "position"
        const val PART = "part"

        /** The TP-Status of a GSM delivery report; null for any other format, or none at all. */
        fun status(intent: Intent): Int? {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu == null || format != GSM) return null
            return SmsMessage.createFromPdu(pdu, format)?.status
        }

        const val GSM = "3gpp"
    }
}
