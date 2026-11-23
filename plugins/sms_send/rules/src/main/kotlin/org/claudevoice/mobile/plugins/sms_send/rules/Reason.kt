package org.claudevoice.mobile.plugins.sms_send.rules

/** How one recipient's text ended, as `sms_send_outcomes` names it (ADR-CVM-010, "Delivered"). */
enum class Outcome(val word: String) {
    SENT("sent"),
    DELIVERED("delivered"),
    STOPPED("stopped"),
    FAILED("failed"),
    UNKNOWN("unknown"),
}

/**
 * Why a text ended as it did: a fixed phrase for the voice, never free text, so no number, name
 * or Android error can reach the hub. Each reason names its own [outcome], so an outcome can only
 * ever carry the phrases ADR-CVM-010 lists for it: `sent` never says "no service".
 */
enum class Reason(val outcome: Outcome, val phrase: String) {
    HANDED(Outcome.SENT, "handed to the network"),
    REPORTED_DELIVERED(Outcome.DELIVERED, "the network reported it delivered"),
    BY_OWNER(Outcome.STOPPED, "stopped by the owner"),
    RESTARTED(Outcome.STOPPED, "the app restarted while it was held"),
    NO_SERVICE(Outcome.FAILED, "no service"),
    RADIO_OFF(Outcome.FAILED, "radio off"),
    NO_SUBSCRIPTION(Outcome.FAILED, "no default subscription"),
    REFUSED(Outcome.FAILED, "refused by Android"),
    REPORTED_FAILED(Outcome.FAILED, "the network reported it failed"),
    MAYBE_SENT(Outcome.UNKNOWN, "it may have been sent; check the text app"),
    ;

    companion object {
        /**
         * The reason for Android's sent report with result [code]: handed over on
         * `Activity.RESULT_OK`, a named radio state on SmsManager's own codes, and "refused by
         * Android" for every other code, so no code of Android's ever reaches the hub.
         */
        fun sent(code: Int): Reason = when (code) {
            RESULT_OK -> HANDED
            RESULT_ERROR_RADIO_OFF -> RADIO_OFF
            RESULT_ERROR_NO_SERVICE -> NO_SERVICE
            else -> REFUSED
        }

        /**
         * The reason for a delivery report with the network's [status] (3GPP TS 23.040, TP-Status):
         * done is delivered; still trying is no outcome yet; anything else failed.
         */
        fun delivered(status: Int): Reason? = when (status) {
            in DONE -> REPORTED_DELIVERED
            in TRYING -> null
            else -> REPORTED_FAILED
        }

        // Android's own values, so the rules need no Android: Activity.RESULT_OK and SmsManager's.
        private const val RESULT_OK = -1
        private const val RESULT_ERROR_RADIO_OFF = 2
        private const val RESULT_ERROR_NO_SERVICE = 4
        private val DONE = 0x00..0x1F
        private val TRYING = 0x20..0x3F
    }
}
