package org.claudevoice.mobile.plugins.sms

import android.content.Context
import org.claudevoice.mobile.capabilities.fsync.Fsync
import org.claudevoice.mobile.plugins.sms.rules.AesGcmSealer
import org.claudevoice.mobile.plugins.sms.rules.FolderSync
import org.claudevoice.mobile.plugins.sms.rules.Inbox
import org.claudevoice.mobile.plugins.sms.rules.QueueFile
import org.claudevoice.mobile.plugins.sms.rules.SecureChance
import org.claudevoice.mobile.plugins.sms.rules.Shared
import java.io.File
import java.time.Instant

/**
 * The one inbox of the app's process, which the receiver and the tool share: one lock and one
 * file. The file is in the no-backup folder, so it never leaves the phone in a backup, and
 * sealed with the Keystore's key. A key the Keystore cannot give makes the queue start again,
 * counting what it held as dropped; it never stops the app.
 */
object SmsInbox {
    private val shared = Shared<Inbox>()

    fun get(context: Context): Inbox =
        shared.get {
            val file = File(context.applicationContext.noBackupFilesDir, "sms-queue")
            val queue = QueueFile(file, AesGcmSealer(KeystoreKeys), FolderSync { Fsync.folder(it) })
            Inbox(queue, SecureChance(), Instant::now)
        }
}
