package org.claudevoice.mobile.capabilities.fsync

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * An `fsync` on a folder, which Linux, and so Android, allows. After it, a file just moved into
 * the folder is still there after a power cut. Each plugin that keeps files hands this to its
 * rules as their folder sync; a folder that is not there throws, so a save is never taken as kept.
 */
object Fsync {
    fun folder(folder: File) {
        FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
