package org.claudevoice.prototype.audiofocus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.Instant

/** Every event of a run, to logcat, to a file `adb pull` can fetch, and to the screen. */
object EventLog {
    const val TAG = "AudioFocusProto"
    private const val FILE = "focus-log.txt"

    @Volatile var run = 1
        private set
    @Volatile var mode = Mode.SELF_DUCK
        private set
    private var startedAt = SystemClock.elapsedRealtime()
    private var file: File? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    /** The shared folder `adb pull` reaches, or the app's own if that cannot be written. */
    fun file(context: Context): File {
        val shared = context.getExternalFilesDir(null)?.let { File(it, FILE) }
        val writable = shared != null && runCatching { shared.appendText("") }.isSuccess
        return if (writable) shared!! else File(context.filesDir, FILE)
    }

    @Synchronized
    fun begin(context: Context, run: Int, mode: Mode) {
        this.run = run
        this.mode = mode
        startedAt = SystemClock.elapsedRealtime()
        file = file(context)
    }

    @Synchronized
    fun add(event: String, fields: Map<String, String> = emptyMap()) {
        val line = LogLine.format(
            Instant.now(), SystemClock.elapsedRealtime() - startedAt, run, mode, event, fields,
        )
        Log.i(TAG, line)
        runCatching { file?.appendText(line + "\n") }
        listeners.toList().forEach { it(line) }
    }

    @Synchronized fun listen(listener: (String) -> Unit) { listeners += listener }
    @Synchronized fun unlisten(listener: (String) -> Unit) { listeners -= listener }
}
