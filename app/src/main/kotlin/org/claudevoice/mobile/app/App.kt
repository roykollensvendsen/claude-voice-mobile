package org.claudevoice.mobile.app

import android.app.Application
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import org.claudevoice.mobile.core.CallLog
import org.claudevoice.mobile.core.FileHubStore
import org.claudevoice.mobile.core.FileLogStore
import org.claudevoice.mobile.core.Plugins
import org.claudevoice.mobile.plugins.status.StatusPlugin

/** The app: the one place that knows which plugins there are, and hands them to the core. */
class App : Application() {
    val plugins by lazy { Plugins(listOf(StatusPlugin())) }

    /** The log of every call, in the app's private files, kept out of backups (ADR-CVM-003). */
    val calls by lazy { CallLog(FileLogStore(File(filesDir, CALLS_FILE))) }

    /** The paired hub, in the folder Android never backs up, so a new phone pairs again (ADR-CVM-003). */
    val hub by lazy { FileHubStore(File(noBackupFilesDir, HUB_FILE)) }

    override fun onCreate() {
        super.onCreate()
        // A quiet phone writes no lines, so old ones also go at start, off the main thread.
        thread(name = "prune-calls", isDaemon = true) {
            try {
                calls.prune()
            } catch (_: IOException) {
                // Tried again at the next call, and when the log is read.
            }
        }
    }

    private companion object {
        const val CALLS_FILE = "calls.jsonl"
        const val HUB_FILE = "hub.json"
    }
}
