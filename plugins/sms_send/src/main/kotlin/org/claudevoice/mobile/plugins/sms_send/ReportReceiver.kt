package org.claudevoice.mobile.plugins.sms_send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import org.claudevoice.mobile.plugins.sms_send.rules.Reports
import java.util.concurrent.Executors

/**
 * Hears Android's sent and delivered reports for each part of each text (ADR-CVM-010,
 * "Delivered"). It is declared in the manifest as not exported, and each report's intent names it
 * by class, so only this app and the system can reach it, on every Android version. The report is
 * written to disk on a worker thread of its own, in the order the reports came, never on the main
 * thread. Only a GSM delivery report is read; any other gives no outcome, since its status is coded
 * otherwise.
 */
class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reports = reports ?: return
        val token = intent.getStringExtra(TOKEN) ?: return
        val position = intent.getIntExtra(POSITION, 0)
        val part = intent.getIntExtra(PART, -1)
        val code = resultCode
        val pending = goAsync()
        worker.execute {
            try {
                when (intent.action) {
                    SENT -> reports.sent(token, position, part, code)
                    DELIVERED -> reports.delivered(token, position, part, status(intent))
                }
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val SENT = "org.claudevoice.mobile.plugins.sms_send.SENT"
        const val DELIVERED = "org.claudevoice.mobile.plugins.sms_send.DELIVERED"
        const val SCHEME = "sms-send"
        const val TOKEN = "token"
        const val POSITION = "position"
        const val PART = "part"
        private const val GSM = "3gpp"

        /** Where the reports go: set once per process, when the plugin is built. */
        @Volatile
        var reports: Reports? = null

        private val worker = Executors.newSingleThreadExecutor()

        /** The TP-Status of a GSM delivery report; null for any other format, or none at all. */
        fun status(intent: Intent): Int? {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu == null || format != GSM) return null
            return SmsMessage.createFromPdu(pdu, format)?.status
        }
    }
}
