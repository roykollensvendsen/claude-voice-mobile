package org.claudevoice.mobile.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Put [bytes] in [file] all at once or not at all: written to `<name>.new`, through to the disk,
 * then renamed over [file]. A power cut leaves the old file or the new one, never half of one.
 * An [IOException] when the new file cannot be put in place; [file] is then as it was.
 */
@Throws(IOException::class)
internal fun replaceWhole(file: File, bytes: ByteArray) {
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
}

/**
 * The paired hub, kept in one file of the app's own, which the app puts in its no-backup folder
 * (ADR-CVM-003). Each pairing replaces it whole, so a crash never leaves a hub half written.
 */
class FileHubStore(private val file: File) : HubStore {
    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    override fun write(text: String) = replaceWhole(file, text.toByteArray(Charsets.UTF_8))
}
