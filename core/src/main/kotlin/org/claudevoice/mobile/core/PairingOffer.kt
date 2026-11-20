package org.claudevoice.mobile.core

/** What the phone's screen offers for pairing, decided here so the screen decides nothing. */
object PairingOffer {
    /**
     * The address the owner gives the hub (`claude-voice machines add`), while the phone
     * listens; none while it listens nowhere, when a code could not be used anyway.
     */
    fun address(status: ListenStatus): String? = (status as? ListenStatus.Listening)?.address?.hostAddress

    /** Whether a code stays on the screen: only while it still works, never once used or late. */
    fun showsCode(pairing: Pairing): Boolean = pairing.isOpen()
}
