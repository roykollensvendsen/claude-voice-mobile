package org.claudevoice.mobile.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Writes a folder's entries to the disk. */
internal fun interface FolderSync {
    @Throws(IOException::class)
    fun sync(folder: File)
}

/** The real one: the folder opened for reading and forced to the disk, as `fsync` on Linux. */
internal val SYNC_FOLDER = FolderSync { folder ->
    FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

/**
 * Put [bytes] in [file] all at once or not at all: written to `<name>.new`, through to the disk,
 * then renamed over [file]. A power cut leaves the old file or the new one, never half of one.
 * An [IOException] when the new file cannot be put in place; [file] is then as it was.
 *
 * After the rename, the folder is written to the disk too, or a power cut could bring the old
 * file back. If that fails, the write still counts: the new file is in place, and an old one
 * coming back after a power cut is a whole file, as before the write.
 */
@Throws(IOException::class)
internal fun replaceWhole(file: File, bytes: ByteArray, folder: FolderSync = SYNC_FOLDER) {
    val next = File(file.parentFile, "${file.name}.new")
    Files.write(
        next.toPath(),
        bytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        StandardOpenOption.SYNC,
    )
    if (!next.renameTo(file)) throw IOException("could not put ${next.name} in place of ${file.name}")
    synced(folder, file.absoluteFile.parentFile)
}

/** [folder] synced, or not if the file system cannot: the file is in place either way. */
private fun synced(folder: FolderSync, dir: File) {
    try {
        folder.sync(dir)
    } catch (_: IOException) {
        // Some file systems cannot sync a folder.
    }
}

/** A small text the app keeps privately, such as the paired hub or the phone's confirmed name. */
interface TextStore {
    fun read(): String?

    /** Replace what is kept, all at once or not at all; an [IOException] when it could not. */
    @Throws(IOException::class)
    fun write(text: String)
}

/**
 * A text kept in one file of the app's own, which the app puts in its no-backup folder
 * (ADR-CVM-003): the paired hub, or the phone's name. Each write replaces it whole, so a crash
 * never leaves it half written.
 */
class FileTextStore(private val file: File) : HubStore {
    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    override fun write(text: String) = replaceWhole(file, text.toByteArray(Charsets.UTF_8))
}
