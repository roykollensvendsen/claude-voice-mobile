package org.claudevoice.mobile.plugins.sms_send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.DELIVERED
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.PART
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.POSITION
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.SCHEME
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.SENT
import org.claudevoice.mobile.plugins.sms_send.ReportReceiver.Companion.TOKEN
import org.claudevoice.mobile.plugins.sms_send.rules.Handover
import org.claudevoice.mobile.plugins.sms_send.rules.Reports

/**
 * The phone's radio for `sms_send` (ADR-CVM-010, "Delivered"). It hands each recipient's text to
 * Android's default text subscription as the parts `divideMessage` gives, with a sent and a
 * delivered report asked for each part, addressed to the [ReportReceiver] by class. Each report's
 * intent is immutable and told apart by its own address, so no stale one is reused.
 */
internal class Radio(private val app: Context, private val reports: Reports?) {
    /** Whether the phone has a default subscription to send texts with. */
    fun subscribed(): Boolean =
        SubscriptionManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /** Hand [handover] to Android; throws as SmsManager does, a SecurityException once sending was taken back. */
    fun send(handover: Handover) {
        val sms = manager()
        val parts = sms.divideMessage(handover.text)
        reports?.expect(handover.token, handover.position, parts.size)
        val sentReports = ArrayList(parts.indices.map { report(SENT, handover, it) })
        val deliveredReports = ArrayList(parts.indices.map { report(DELIVERED, handover, it) })
        sms.sendMultipartTextMessage(handover.number, null, parts, sentReports, deliveredReports)
    }

    private fun report(action: String, handover: Handover, part: Int): PendingIntent {
        val address = Uri.fromParts(SCHEME, "${handover.token}/${handover.position}/$part", null)
        val intent = Intent(action, address, app, ReportReceiver::class.java)
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
}
