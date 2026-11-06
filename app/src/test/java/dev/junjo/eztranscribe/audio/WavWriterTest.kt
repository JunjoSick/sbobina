package dev.junjo.eztranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun `header describes 16 kHz mono 16-bit PCM`() {
        val bytes = write(FloatArray(100), sampleRate = 16_000)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44 + 200, bytes.size)
        assertEquals("RIFF", ascii(bytes, 0))
        assertEquals(36 + 200, header.getInt(4))
        assertEquals("WAVE", ascii(bytes, 8))
        assertEquals("fmt ", ascii(bytes, 12))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())        // PCM
        assertEquals(1, header.getShort(22).toInt())        // mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))             // byte rate
        assertEquals(2, header.getShort(32).toInt())        // block align
        assertEquals(16, header.getShort(34).toInt())       // bits per sample
        assertEquals("data", ascii(bytes, 36))
        assertEquals(200, header.getInt(40))
    }

    @Test
    fun `samples follow the header little-endian, clamped at full scale`() {
        val bytes = write(floatArrayOf(1f, -1f, 0f, 2f, -3f), sampleRate = 16_000)
        val body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(32767, body.getShort(44).toInt())
        assertEquals(-32767, body.getShort(46).toInt())
        assertEquals(0, body.getShort(48).toInt())
        assertEquals(32767, body.getShort(50).toInt())     // 2.0 clamped, not wrapped
        assertEquals(-32767, body.getShort(52).toInt())    // -3.0 clamped, not wrapped
    }

    private fun write(samples: FloatArray, sampleRate: Int): ByteArray =
        ByteArrayOutputStream().also { WavWriter.writePcm16Mono(samples, sampleRate, it) }.toByteArray()

    private fun ascii(bytes: ByteArray, offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
}
