package org.claudevoice.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class PairingOfferTest {
    private val documentation = InetAddress.getByName("192.0.2.10")

    @Test
    fun `while the phone listens, the screen offers pairing and gives the address the hub adds`() {
        assertEquals("192.0.2.10", PairingOffer.address(ListenStatus.Listening(documentation, PhoneAgent.PORT)))
    }

    @Test
    fun `while the phone listens nowhere, the screen offers no pairing`() {
        assertNull(PairingOffer.address(ListenStatus.Stopped("Tailscale is off.")))
    }

    private var now = Instant.parse("2026-11-20T21:00:00Z")
    private val pairing = Pairing({ now }, SecureRandom())

    @Test
    fun `a code is shown only while it still works, so an expired one leaves the screen`() {
        assertFalse(PairingOffer.showsCode(pairing))
        pairing.newCode()
        assertTrue(PairingOffer.showsCode(pairing))
        now += Duration.ofMinutes(10)
        assertFalse(PairingOffer.showsCode(pairing))
    }

    @Test
    fun `a code that paired leaves the screen at once`() {
        val code = pairing.newCode()
        val hub = InetAddress.getByName("100.64.0.9")
        pairing.pair(code, hub) { "hub" }
        assertFalse(PairingOffer.showsCode(pairing))
    }
}
