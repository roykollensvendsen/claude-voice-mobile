package org.claudevoice.prototype.audiofocus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneWavTest {
    private val wav = ToneWav.make(seconds = 2, sampleRate = 8000)
    private val le = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun the_test_sound_is_a_plain_16_bit_mono_wav() {
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, le.getShort(22).toInt())
        assertEquals(8000, le.getInt(24))
        assertEquals(16, le.getShort(34).toInt())
        assertEquals(2 * 8000 * 2, le.getInt(40))
        assertEquals(44 + 2 * 8000 * 2, wav.size)
    }

    @Test
    fun the_test_sound_is_never_silent_for_long() {
        val samples = (0 until 2 * 8000).map { le.getShort(44 + it * 2).toInt() }
        samples.chunked(800).forEach { window ->
            assertTrue(window.maxOf { kotlin.math.abs(it) } > 1000)
        }
    }
}
