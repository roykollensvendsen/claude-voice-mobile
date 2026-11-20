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

/** Makes what a folder lists survive a power cut, as `fsync` on the folder does. */
fun interface FolderSync {
    @Throws(IOException::class)
    fun sync(folder: File)
}

/**
 * The queue kept in one file, sealed (ADR-CVM-010). The plugin puts the file in the app's
 * no-backup folder. Only how many messages it holds is outside the seal, so that a lost key
 * can still count them as dropped. Each save replaces the file whole.
 */
class QueueFile(private val file: File, private val sealer: Sealer, private val folderSync: FolderSync) {
    /**
     * The queue kept, a new one when there is none, or a new one that counts a lost key's
     * messages. A file whose start cannot be read counts one lost, since it may have held any:
     * the hub may be told of too many dropped, never too few.
     */
    fun load(chance: Chance): SmsQueue {
        if (!file.exists()) return SmsQueue.fresh(chance)
        val kept = kept()
        return if (kept == null) SmsQueue.afterLostKey(1, chance) else opened(kept, chance)
    }

    private fun opened(kept: Kept, chance: Chance): SmsQueue =
        try {
            SmsQueue.fromBytes(sealer.open(kept.sealed), chance)
        } catch (_: GeneralSecurityException) {
            SmsQueue.afterLostKey(kept.held, chance)
        } catch (_: IOException) {
            SmsQueue.afterLostKey(kept.held, chance)
        }

    /** How many messages the file holds, and its sealed part. */
    private class Kept(val held: Int, val sealed: ByteArray)

    // None when the file is not one of ours or its count cannot be. The count is outside the
    // seal, so it is held to what the queue can hold. Read whole into memory first, so nothing
    // is left open.
    private fun kept(): Kept? =
        try {
            val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
            val ours = input.readInt() == MAGIC
            val held = input.readInt()
            if (ours && held >= 0) Kept(minOf(held, SmsQueue.MOST_KEPT), input.readBytes()) else null
        } catch (_: IOException) {
            null
        }

    /**
     * Keep [queue], all at once or not at all: written beside the file, then moved over it, and
     * the folder synced, so that after a power cut the old file, and the numbers it would give
     * again, never comes back.
     */
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
        folderSync.sync(file.absoluteFile.parentFile)
    }

    private companion object {
        /** "SMSQ", so a file that is not one is never read as one. */
        const val MAGIC = 0x534D5351
    }
}
