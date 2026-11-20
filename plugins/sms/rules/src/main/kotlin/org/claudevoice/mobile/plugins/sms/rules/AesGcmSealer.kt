package org.claudevoice.mobile.plugins.sms.rules

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the seal's key comes from: on the phone, the Keystore. */
interface KeySource {
    /** The key, made when there is none. */
    @Throws(GeneralSecurityException::class)
    fun key(): SecretKey

    /** Drop a key the platform no longer accepts, so the next [key] makes a new one. */
    @Throws(GeneralSecurityException::class)
    fun forget()
}

/**
 * Seals with AES in GCM mode, so a changed byte is noticed as well as hidden. The sealed bytes
 * are the cipher's own nonce, then the text and its tag. The key is asked for each time: on the
 * phone it is the Keystore's, which makes a new one when the old is gone, so the old file no
 * longer opens and its messages are counted as dropped.
 */
class AesGcmSealer(private val keys: KeySource) : Sealer {
    constructor(key: SecretKey) : this(
        object : KeySource {
            override fun key() = key

            override fun forget() = Unit
        },
    )

    private fun key() = keys.key()

    /**
     * The sealed bytes. A key the platform no longer accepts is forgotten and a new one tried,
     * once; any other failure is an [IOException], as a failed save is.
     */
    @Throws(IOException::class)
    override fun seal(plain: ByteArray): ByteArray =
        try {
            sealedTrying(plain)
        } catch (failed: GeneralSecurityException) {
            throw IOException("could not seal", failed)
        }

    private fun sealedTrying(plain: ByteArray): ByteArray =
        try {
            sealed(plain)
        } catch (_: InvalidKeyException) {
            keys.forget()
            sealed(plain)
        }

    private fun sealed(plain: ByteArray): ByteArray =
        guarded {
            // The cipher picks the nonce, as the Keystore insists for its own keys.
            val cipher = Cipher.getInstance(MODE)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(plain)
        }

    @Throws(GeneralSecurityException::class)
    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE + TAG) throw GeneralSecurityException("too short to be sealed")
        return guarded {
            val cipher = Cipher.getInstance(MODE)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG * Byte.SIZE_BITS, sealed, 0, NONCE))
            cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
        }
    }

    // The Keystore can fail inside the cipher with an unchecked ProviderException. Left alone it
    // would kill the receiver's thread and lose the message uncounted; as a security failure it is
    // a failed save when sealing and a lost key when opening, both of which the queue counts.
    private fun <T> guarded(work: () -> T): T =
        try {
            work()
        } catch (failed: ProviderException) {
            throw GeneralSecurityException("the key store failed", failed)
        }

    companion object {
        /** The nonce's bytes, and the tag's. */
        const val NONCE = 12
        const val TAG = 16
        private const val MODE = "AES/GCM/NoPadding"
    }
}
