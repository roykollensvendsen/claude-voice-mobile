package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The queue's seal: AES in GCM mode, so a changed byte is noticed, with a key the caller holds.
 * On the phone the key is the Keystore's; here it is made in memory.
 */
class AesGcmSealerTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val plain = "Ses vi klokka seks?".toByteArray()

    @Test
    fun `what is sealed opens to the same bytes`() {
        val sealer = AesGcmSealer(key())
        assertArrayEquals(plain, sealer.open(sealer.seal(plain)))
        assertArrayEquals(ByteArray(0), sealer.open(sealer.seal(ByteArray(0))))
    }

    @Test
    fun `the sealed bytes hold no plain text, and two seals of the same bytes differ`() {
        val sealer = AesGcmSealer(key())
        val once = sealer.seal(plain)
        assertFalse(String(once, Charsets.ISO_8859_1).contains("klokka"))
        assertFalse(once.contentEquals(sealer.seal(plain)))
        assertEquals(AesGcmSealer.NONCE + plain.size + AesGcmSealer.TAG, once.size)
    }

    @Test
    fun `a changed byte anywhere is noticed`() {
        val sealer = AesGcmSealer(key())
        val sealed = sealer.seal(plain)
        for (i in sealed.indices) {
            val changed = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows<GeneralSecurityException>("byte $i") { sealer.open(changed) }
        }
    }

    @Test
    fun `another key cannot open it, as after the Keystore lost the key`() {
        val sealed = AesGcmSealer(key()).seal(plain)
        assertThrows<GeneralSecurityException> { AesGcmSealer(key()).open(sealed) }
    }

    @Test
    fun `bytes too short to hold a nonce and a tag are refused as unopenable`() {
        val sealer = AesGcmSealer(key())
        for (size in listOf(0, AesGcmSealer.NONCE, AesGcmSealer.NONCE + AesGcmSealer.TAG - 1)) {
            assertThrows<GeneralSecurityException>("$size bytes") { sealer.open(ByteArray(size)) }
        }
    }

    @Test
    fun `the key is asked for each time, so a key the phone made again is used`() {
        var asked = 0
        val held = key()
        val sealer = AesGcmSealer { asked += 1; held }
        sealer.open(sealer.seal(plain))
        assertEquals(2, asked)
    }
}
