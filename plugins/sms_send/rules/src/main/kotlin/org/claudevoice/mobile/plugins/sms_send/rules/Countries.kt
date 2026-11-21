package org.claudevoice.mobile.plugins.sms_send.rules

import java.util.Locale

/**
 * The country a contact's number saved without a country code is read in, such as "000 00 034".
 * The SIM's country comes first, then the phone's own region setting. Never the network's: while
 * roaming it would put a foreign code on a home contact's number. A number read this way is a
 * guess, so it is offered only as a choice, and its read-back says the whole number before the
 * owner's yes.
 */
object Countries {
    /** The country, in capitals, from [sim] or else [locale]; null when both are blank. */
    fun chosen(sim: String?, locale: String?): String? {
        val country = sim?.trim().takeUnless { it.isNullOrEmpty() } ?: locale?.trim()
        return country?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
    }
}
