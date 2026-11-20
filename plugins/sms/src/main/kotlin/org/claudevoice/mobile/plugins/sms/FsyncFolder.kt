package org.claudevoice.mobile.plugins.sms

import org.claudevoice.mobile.plugins.sms.rules.FolderSync
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The phone's [FolderSync]: an `fsync` on the folder itself, which Linux, and so Android,
 * allows. After it, a file just moved into the folder is still there after a power cut. The
 * receiver hands it to the queue's file in the next step.
 */
object FsyncFolder : FolderSync {
    override fun sync(folder: File) {
        FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
