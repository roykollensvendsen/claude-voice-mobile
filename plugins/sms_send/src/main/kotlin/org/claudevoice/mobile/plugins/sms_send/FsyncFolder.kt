package org.claudevoice.mobile.plugins.sms_send

import org.claudevoice.mobile.plugins.sms_send.rules.FolderSync
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The phone's [FolderSync]: an `fsync` on the folder itself, which Linux, and so Android, allows.
 * After it, a file just moved into the folder is still there after a power cut. The `sms` plugin
 * has its own, since one plugin never reaches into another.
 */
object FsyncFolder : FolderSync {
    override fun sync(folder: File) {
        FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
