package org.claudevoice.mobile.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.InetAddress
import kotlin.concurrent.thread
import org.claudevoice.mobile.core.CallLog
import org.claudevoice.mobile.core.FileTextStore
import org.claudevoice.mobile.core.MachineName
import org.claudevoice.mobile.core.OnceNamed
import org.claudevoice.mobile.core.FileLogStore
import org.claudevoice.mobile.core.AgentInfo
import org.claudevoice.mobile.core.PhoneAgent
import org.claudevoice.mobile.core.Plugins
import org.claudevoice.mobile.core.SettingsKeeper
import org.claudevoice.mobile.core.hubName
import org.claudevoice.mobile.core.suggestedMachine

/** The app: the one place that knows which plugins there are, and hands them to the core. */
class App : Application() {
    val plugins by lazy { Plugins(BUILT_PLUGINS.map { (folder, build) -> BuiltPlugin.named(folder, build(this)) }) }

    /** The log of every call, in the app's private files, kept out of backups (ADR-CVM-003). */
    val calls by lazy { CallLog(FileLogStore(File(filesDir, CALLS_FILE))) }

    /** The paired hub, in the folder Android never backs up, so a new phone pairs again (ADR-CVM-003). */
    val hub by lazy { FileTextStore(File(noBackupFilesDir, HUB_FILE)) }

    /** The phone's settings, set only on its own screens (ADR-CVM-003). */
    val settings by lazy { SettingsKeeper() }

    /** The name the owner confirmed for the phone; none before. A different one is refused once kept. */
    val machine by lazy { MachineName(FileTextStore(File(noBackupFilesDir, MACHINE_FILE))) }

    /** What the owner is offered to confirm: a name made from the phone's model. */
    val suggestedName: String get() = suggestedMachine(Build.MODEL.orEmpty())

    /**
     * The agent the hub talks to, built once the owner has confirmed the phone's name, with
     * [calls] as its log: the log the screen reads. Null before, so nothing can pair under a
     * name the owner did not choose. Nothing starts it yet; the app's service will.
     */
    private val agent by lazy { OnceNamed({ machine.name }, ::build) }

    fun agent(): PhoneAgent? = agent.get()

    private fun build(name: String): PhoneAgent {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: UNKNOWN_VERSION
        val nameOf = { address: InetAddress ->
            hubName(address.canonicalHostName.orEmpty(), address.hostAddress.orEmpty())
        }
        return PhoneAgent(hub, calls, nameOf) { place ->
            AgentInfo(plugins, version, name, settings.settings, place)
        }
    }

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
        const val MACHINE_FILE = "machine.json"
        const val UNKNOWN_VERSION = "0.0.0"
    }
}
