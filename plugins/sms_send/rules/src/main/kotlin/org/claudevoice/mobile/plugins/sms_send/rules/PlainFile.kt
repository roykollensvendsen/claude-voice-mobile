package org.claudevoice.mobile.plugins.sms_send.rules

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Makes what a folder lists survive a power cut, as `fsync` on the folder does. */
fun interface FolderSync {
    @Throws(IOException::class)
    fun sync(folder: File)
}

/**
 * One plain file of the plugin's, in the app's no-backup folder (ADR-CVM-010): written whole or
 * not at all, and the folder synced, so that after a power cut no older copy comes back. A
 * damaged file is copied aside under a timed name in the same folder, never overwritten, and only
 * the last [KEPT] of those are kept. The file itself stays until the caller writes over it or
 * removes it, so a crash in between finds it damaged again rather than gone.
 */
class PlainFile(private val file: File, private val folderSync: FolderSync) {
    private val folder: File get() = file.absoluteFile.parentFile

    private val aside: String get() = "${file.name}$ASIDE"

    /** The bytes kept, or null when there is no file. */
    @Throws(IOException::class)
    fun read(): ByteArray? = if (file.exists()) file.readBytes() else null

    /** Keep [bytes], written beside the file, then moved over it. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray) {
        val next = File(folder, "${file.name}.new")
        Files.write(
            next.toPath(),
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        )
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        folderSync.sync(folder)
    }

    /** Copy a damaged file aside under the time [at], and forget all but the last [KEPT] set aside. */
    @Throws(IOException::class)
    fun setAside(at: Instant) {
        if (!file.exists()) return
        var millis = at.toEpochMilli()
        // Two set aside in the same millisecond each keep their own name.
        while (File(folder, "$aside$millis").exists()) millis += 1
        // Synced, so the copy is on disk before anything is written over the file it came from.
        Files.write(
            File(folder, "$aside$millis").toPath(),
            Files.readAllBytes(file.toPath()),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        )
        // Newest first by name: the millis have 13 digits from 2001 to 2286, so name order is time order,
        // and the same on every file system, whatever order it lists the folder in.
        val older = folder.list().orEmpty().filter { it.startsWith(aside) }.sortedDescending()
        older.drop(KEPT).forEach { Files.deleteIfExists(File(folder, it).toPath()) }
        folderSync.sync(folder)
    }

    /** Remove the file, so it reads as none. */
    @Throws(IOException::class)
    fun remove() {
        Files.deleteIfExists(file.toPath())
        folderSync.sync(folder)
    }

    private companion object {
        const val ASIDE = ".damaged-"
        const val KEPT = 3
    }
}
