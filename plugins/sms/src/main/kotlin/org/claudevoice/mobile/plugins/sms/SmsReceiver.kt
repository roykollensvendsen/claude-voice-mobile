package org.claudevoice.mobile.plugins.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.claudevoice.mobile.plugins.sms.rules.Arrivals
import java.time.ZoneId

/**
 * Hears each new text message, through the system's `SMS_RECEIVED` broadcast, and puts it in the
 * queue (ADR-CVM-010). It reads and does nothing else: the default text app gets every message
 * as before. The time kept is the phone's own when the broadcast came. Saving runs off the main
 * thread, while the system keeps the app alive for it.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
            .map { Arrivals.Part(it.originatingAddress, it.messageBody) }
        val zone = ZoneId.systemDefault()
        val pending = goAsync()
        Thread {
            try {
                keep(context, parts, zone)
            } finally {
                pending.finish()
            }
        }.start()
    }

    // Never logs a sender or a text: only that a message could not be saved.
    private fun keep(context: Context, parts: List<Arrivals.Part>, zone: ZoneId) {
        val inbox = SmsInbox.get(context)
        for (message in Arrivals.joined(parts)) {
            val kept = inbox.receive(message.from, message.body, zone, NO_COUNTRY)
            if (!kept) Log.w(TAG, "a new message could not be saved")
        }
    }

    private companion object {
        const val TAG = "sms"

        // Until the phone's own store turns national numbers into E.164 (ADR-CVM-010, the next
        // step), they are kept as their digits, still a number, as the answer allows.
        val NO_COUNTRY: (String) -> String? = { null }
    }
}
