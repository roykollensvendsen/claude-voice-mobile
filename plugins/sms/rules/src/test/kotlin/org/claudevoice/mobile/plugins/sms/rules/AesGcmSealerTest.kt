package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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

    /** Keys handed out in turn; [forgotten] counts how often the platform was told to drop one. */
    private class Keys(vararg keys: () -> SecretKey) : KeySource {
        private val queue = ArrayDeque(keys.toList())
        var asked = 0
        var forgotten = 0

        override fun key(): SecretKey {
            asked += 1
            return (if (queue.size > 1) queue.first() else queue.last())()
        }

        override fun forget() {
            forgotten += 1
            if (queue.size > 1) queue.removeFirst()
        }
    }

    // A key AES refuses at once, as the Keystore refuses one it has permanently invalidated.
    private val refused: () -> SecretKey = { SecretKeySpec(ByteArray(5), "AES") }

    @Test
    fun `the key is asked for each time, so a key the phone made again is used`() {
        val held = key()
        val keys = Keys({ held })
        val sealer = AesGcmSealer(keys)
        sealer.open(sealer.seal(plain))
        assertEquals(2, keys.asked)
    }

    @Test
    fun `a key the platform no longer accepts is forgotten once, and the seal made with a new one`() {
        val fresh = key()
        val keys = Keys(refused, { fresh })
        val sealed = AesGcmSealer(keys).seal(plain)
        assertEquals(1, keys.forgotten)
        assertArrayEquals(plain, AesGcmSealer(fresh).open(sealed))
    }

    @Test
    fun `a key still refused after it was forgotten fails the seal as a failed save, without trying forever`() {
        val keys = Keys(refused)
        assertThrows<IOException> { AesGcmSealer(keys).seal(plain) }
        assertEquals(1, keys.forgotten)
    }

    @Test
    fun `a key that cannot be had at all fails the seal as a failed save, and the open as a lost key`() {
        val gone = object : KeySource {
            override fun key(): SecretKey = throw KeyStoreException("the Keystore is not there")

            override fun forget() = Unit
        }
        assertThrows<IOException> { AesGcmSealer(gone).seal(plain) }
        val sealed = AesGcmSealer(key()).seal(plain)
        assertThrows<GeneralSecurityException> { AesGcmSealer(gone).open(sealed) }
    }

    // A key the cipher itself fails on with the unchecked exception the Keystore throws.
    private object Broken : SecretKey {
        private fun readResolve(): Any = Broken

        override fun getAlgorithm() = "AES"

        override fun getFormat() = "RAW"

        override fun getEncoded(): ByteArray = throw ProviderException("the Keystore failed in the cipher")
    }

    @Test
    fun `a cipher the Keystore fails inside fails the seal as a failed save, and the open as a lost key`() {
        val sealed = AesGcmSealer(key()).seal("en".toByteArray())
        val sealer = AesGcmSealer(Keys({ Broken }))
        assertThrows<IOException> { sealer.seal("to".toByteArray()) }
        assertThrows<GeneralSecurityException> { sealer.open(sealed) }
    }
}
