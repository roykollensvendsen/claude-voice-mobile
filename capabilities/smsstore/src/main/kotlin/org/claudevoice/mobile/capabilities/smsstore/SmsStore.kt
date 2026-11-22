package org.claudevoice.mobile.capabilities.smsstore

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.PhoneNumberUtils

/**
 * The phone's own message store (ADR-CVM-010). To `sms_send` it answers one question only: has the
 * owner sent a message to this number? It answers yes or no, and never hands back a message, a
 * list or a count. [permitted] says whether the app holds READ_SMS; this module never asks for
 * it. [sent] reads the address of every message in the sent box. [toE164] reads an address
 * written without its "+" in the phone's country, or gives null when it cannot.
 */
class SmsStore(
    private val permitted: () -> Boolean,
    private val sent: () -> Sequence<String?>,
    private val toE164: (String) -> String?,
) {
    /**
     * Whether a message was sent to [e164], the number in full international form, or null when
     * the app may not read texts. Nothing is read without leave.
     */
    fun sentBefore(e164: String): Boolean? =
        if (permitted()) sent().filterNotNull().any { international(it, toE164) == e164 } else null

    companion object {
        private val GAPS = Regex("""[\s()-]+""")

        /**
         * The address as a number in full international form: as written when it starts with
         * "+", else read by [toE164]; null when it is empty or cannot be read.
         */
        fun international(address: String, toE164: (String) -> String?): String? {
            val written = GAPS.replace(address, "")
            return when {
                written.isEmpty() -> null
                written.startsWith("+") -> written
                else -> toE164(written)
            }
        }

        /**
         * The store on this phone, read each time it is asked. [country] is the two-letter country
         * an address without a code is read in, or null for none.
         */
        fun on(context: Context, country: () -> String?): SmsStore {
            val app = context.applicationContext
            return SmsStore(
                { app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED },
                { addresses(app.contentResolver) },
                { raw -> country()?.let { PhoneNumberUtils.formatNumberToE164(raw, it) } },
            )
        }

        private fun addresses(resolver: ContentResolver): Sequence<String?> =
            resolver.query(Telephony.Sms.Sent.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, null)
                ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                .orEmpty()
                .asSequence()
    }
}
