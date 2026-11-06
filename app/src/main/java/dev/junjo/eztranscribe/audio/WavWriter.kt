package dev.junjo.eztranscribe.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes decoded audio back out, so a decode can be checked by ear and by tools on the PC. */
object WavWriter {

    private const val HEADER_BYTES = 44

    /** 16-bit PCM mono, little-endian: the form every player accepts. */
    fun writePcm16Mono(samples: FloatArray, sampleRate: Int, out: OutputStream) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ascii("RIFF"))
            putInt(HEADER_BYTES - 8 + dataBytes)
            put(ascii("WAVE"))
            put(ascii("fmt "))
            putInt(16)                  // fmt chunk size
            putShort(1.toShort())       // PCM
            putShort(1.toShort())       // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)      // byte rate
            putShort(2.toShort())       // block align
            putShort(16.toShort())      // bits per sample
            put(ascii("data"))
            putInt(dataBytes)
        }
        out.write(header.array())

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) body.putShort(toPcm16(sample))
        out.write(body.array())
    }

    /** Clamps first: a decoder can overshoot full scale slightly, and wrapping would click. */
    fun toPcm16(sample: Float): Short = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
}
