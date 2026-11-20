package org.claudevoice.mobile.plugins.sms.rules

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals with AES in GCM mode, so a changed byte is noticed as well as hidden. The sealed bytes
 * are the cipher's own nonce, then the text and its tag. [key] is asked each time: on the phone it
 * is the Keystore's, which makes a new one when the old is gone, so the old file no longer opens.
 */
class AesGcmSealer(private val key: () -> SecretKey) : Sealer {
    constructor(key: SecretKey) : this({ key })

    override fun seal(plain: ByteArray): ByteArray {
        // The cipher picks the nonce, as the Keystore insists for its own keys.
        val cipher = Cipher.getInstance(MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    @Throws(GeneralSecurityException::class)
    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE + TAG) throw GeneralSecurityException("too short to be sealed")
        val cipher = Cipher.getInstance(MODE)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG * Byte.SIZE_BITS, sealed, 0, NONCE))
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    companion object {
        /** The nonce's bytes, and the tag's. */
        const val NONCE = 12
        const val TAG = 16
        private const val MODE = "AES/GCM/NoPadding"
    }
}
