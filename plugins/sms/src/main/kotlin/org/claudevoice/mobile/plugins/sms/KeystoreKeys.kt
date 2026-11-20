package org.claudevoice.mobile.plugins.sms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.claudevoice.mobile.plugins.sms.rules.KeySource
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The queue's key, kept in Android's Keystore, where it never leaves the phone. It is bound
 * neither to the owner's lock screen nor to an unlocked phone, since messages arrive while the
 * phone is locked. When it is gone or no longer accepted, a new one is made, the old file no
 * longer opens, and its messages are counted as dropped.
 */
object KeystoreKeys : KeySource {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "sms-queue"
    private const val BITS = 256

    override fun key(): SecretKey =
        guarded {
            val store = KeyStore.getInstance(STORE).apply { load(null) }
            store.getKey(ALIAS, null) as? SecretKey ?: made()
        }

    override fun forget() {
        guarded { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun made(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    // The Keystore reports some failures as unchecked ProviderExceptions; the seal treats a
    // KeyStoreException as a key it cannot have, so the app never crashes on one.
    private fun <T> guarded(work: () -> T): T =
        try {
            work()
        } catch (failed: ProviderException) {
            throw KeyStoreException(failed)
        }
}
