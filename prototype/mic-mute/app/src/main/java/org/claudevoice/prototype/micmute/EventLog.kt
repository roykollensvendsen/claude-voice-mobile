package org.claudevoice.prototype.micmute

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Every event, to logcat, to a file in the app's private storage, and to the screen. The file
 * holds times and states only, never sound; removing the app deletes it. "Save log" copies it to
 * Downloads, so the owner can send it to the laptop, where it stays outside git.
 */
object EventLog {
    const val TAG = "MicMuteProto"
    private const val FILE = "mic-mute-log.txt"

    @Volatile var measure = "M1a"
    private val startedAt = SystemClock.elapsedRealtime()
    private var file: File? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun open(context: Context) {
        if (file == null) file = File(context.filesDir, FILE)
    }

    @Synchronized
    fun add(event: String, fields: Map<String, String> = emptyMap()) {
        val line = LogLine.format(Instant.now(), SystemClock.elapsedRealtime() - startedAt, measure, event, fields)
        Log.i(TAG, line)
        runCatching { file?.appendText(line + "\n") }
        listeners.toList().forEach { it(line) }
    }

    /** Copies the log to Downloads under a name with the time, and returns that name. */
    @Synchronized
    fun saveToDownloads(context: Context): String? {
        val source = file?.takeIf { it.isFile } ?: return null
        val name = "mic-mute-${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        return name
    }

    @Synchronized fun listen(listener: (String) -> Unit) { listeners += listener }
    @Synchronized fun unlisten(listener: (String) -> Unit) { listeners -= listener }
}
