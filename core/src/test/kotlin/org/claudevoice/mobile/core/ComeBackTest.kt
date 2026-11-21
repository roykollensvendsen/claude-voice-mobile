package org.claudevoice.mobile.core

import java.net.InetAddress
import java.security.SecureRandom
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class ComeBackTest {
    private val phone = InetAddress.getByName("100.64.0.2")
    private val hubAt = InetAddress.getByName("100.64.0.1")

    private class Memory(var text: String? = null) : HubStore {
        override fun read() = text

        override fun write(text: String) {
            this.text = text
        }
    }

    private companion object {
        const val BOOT = "android.intent.action.BOOT_COMPLETED"
        const val UPDATED = "android.intent.action.MY_PACKAGE_REPLACED"
    }

    private fun pairedStore(): Memory {
        val store = Memory()
        val pairing = Pairing(clock = { Instant.EPOCH }, random = SecureRandom())
        val paired = pairing.pair(pairing.newCode(), hubAt) { "laptop-a" } as PairResult.Paired
        assertTrue(KeptGate(store, own = phone, port = 8812, machine = "phone-a").paired(paired))
        return store
    }

    @Test
    fun `a named phone paired with a hub listens again after a restart or an update`() {
        assertTrue(ComeBack.starts(BOOT, name = "phone-a", hub = pairedStore()))
        assertTrue(ComeBack.starts(UPDATED, name = "phone-a", hub = pairedStore()))
    }

    @Test
    fun `any other broadcast starts nothing, the locked boot included`() {
        val others = listOf("android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.SCREEN_ON", "", null)
        for (other in others) {
            assertFalse(ComeBack.starts(other, name = "phone-a", hub = pairedStore()), "$other")
        }
    }

    @Test
    fun `a phone that was never paired starts nothing by itself`() {
        assertFalse(ComeBack.starts(BOOT, name = "phone-a", hub = Memory()))
    }

    @Test
    fun `a kept pairing that cannot be read starts nothing by itself`() {
        assertFalse(ComeBack.starts(BOOT, name = "phone-a", hub = Memory("{not a pairing")))
    }

    @Test
    fun `a phone with no confirmed name starts nothing by itself, whatever is kept`() {
        assertFalse(ComeBack.starts(BOOT, name = null, hub = pairedStore()))
    }
}
