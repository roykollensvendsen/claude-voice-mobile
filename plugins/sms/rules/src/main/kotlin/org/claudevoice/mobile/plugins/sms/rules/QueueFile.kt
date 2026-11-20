package org.claudevoice.mobile.plugins.sms.rules

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException

/** Seals the queue's bytes with a key that never leaves the phone: the Keystore's, on Android. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray

    /** The bytes [seal] was given; a [GeneralSecurityException] when the key is gone. */
    @Throws(GeneralSecurityException::class)
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The queue kept in one file, sealed (ADR-CVM-010). The plugin puts the file in the app's
 * no-backup folder. Only how many messages it holds is outside the seal, so that a lost key
 * can still count them as dropped. Each save replaces the file whole.
 */
class QueueFile(private val file: File, private val sealer: Sealer) {
    /** The queue kept, a new one when there is none, or a new one that counts a lost key's messages. */
    fun load(chance: Chance): SmsQueue {
        val kept = kept() ?: return SmsQueue.fresh(chance)
        return try {
            SmsQueue.fromBytes(sealer.open(kept.sealed), chance)
        } catch (_: GeneralSecurityException) {
            SmsQueue.afterLostKey(kept.held, chance)
        } catch (_: IOException) {
            SmsQueue.afterLostKey(kept.held, chance)
        }
    }

    /** How many messages the file holds, and its sealed part. */
    private class Kept(val held: Int, val sealed: ByteArray)

    // None when there is no file, or it is not one of ours. Read whole into memory first, so
    // nothing is left open.
    private fun kept(): Kept? {
        if (!file.exists()) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
            if (input.readInt() == MAGIC) Kept(input.readInt(), input.readBytes()) else null
        } catch (_: IOException) {
            null
        }
    }

    /** Keep [queue], all at once or not at all: written beside the file, then moved over it. */
    @Throws(IOException::class)
    fun save(queue: SmsQueue) {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(MAGIC)
        out.writeInt(queue.size)
        out.write(sealer.seal(queue.toBytes()))
        val next = File(file.parentFile, "${file.name}.new")
        Files.write(
            next.toPath(),
            bytes.toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        )
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        /** "SMSQ", so a file that is not one is never read as one. */
        const val MAGIC = 0x534D5351
    }
}
