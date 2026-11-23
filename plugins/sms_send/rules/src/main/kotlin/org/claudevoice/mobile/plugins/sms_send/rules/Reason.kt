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
}
