package org.claudevoice.prototype.audiofocus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A test sound made on the phone, so nothing copyrighted ships: a slow arpeggio that never
 * falls silent, so a duck or a pause is heard at once.
 */
object ToneWav {
    private val notesHz = doubleArrayOf(220.0, 277.18, 329.63, 440.0, 329.63, 277.18)
    private const val NOTE_SECONDS = 0.4
    private const val AMPLITUDE = 0.35

    fun make(seconds: Int, sampleRate: Int = 22_050): ByteArray {
        val samples = seconds * sampleRate
        val dataBytes = samples * 2
        val out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(dataBytes)
        var phase = 0.0
        for (i in 0 until samples) {
            val note = notesHz[((i.toDouble() / sampleRate / NOTE_SECONDS).toInt()) % notesHz.size]
            phase += 2 * PI * note / sampleRate
            out.putShort((sin(phase) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort())
        }
        return out.array()
    }
}
