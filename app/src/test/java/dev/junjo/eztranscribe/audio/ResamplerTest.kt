package dev.junjo.eztranscribe.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    @Test
    fun `48000 to 16000 Hz keeps a 1 kHz tone at its pitch and level`() {
        val output = Resampler(48_000, 16_000).process(sine(1000.0, 48_000, seconds = 1.0))

        assertEquals(16_000, output.size)
        val interior = output.interior()
        assertEquals(1000.0, frequencyOf(interior, 16_000), 2.0)
        assertEquals(FULL_SCALE_SINE_RMS, rms(interior), 0.01)
    }

    @Test
    fun `44100 to 16000 Hz keeps a 440 Hz tone at its pitch and level`() {
        // The awkward ratio: 160 output samples per 441 input samples, so 160 distinct kernels.
        // A slip in the phase arithmetic shows up here as a pitch shift.
        val output = Resampler(44_100, 16_000).process(sine(440.0, 44_100, seconds = 1.0))

        assertEquals(16_000, output.size)
        val interior = output.interior()
        assertEquals(440.0, frequencyOf(interior, 16_000), 2.0)
        assertEquals(FULL_SCALE_SINE_RMS, rms(interior), 0.01)
    }

    @Test
    fun `a tone above the new Nyquist is filtered out, not folded into the speech band`() {
        // Plain decimation would fold 10 kHz down to 6 kHz at full level.
        val output = Resampler(48_000, 16_000).process(sine(10_000.0, 48_000, seconds = 1.0))

        val leaked = rms(output.interior())
        assertTrue("leaked RMS $leaked", leaked < 0.01)
    }

    @Test
    fun `constant input stays constant away from the edges`() {
        val output = Resampler(44_100, 16_000).process(FloatArray(44_100) { 0.5f })

        for (sample in output.interior()) assertEquals(0.5f, sample, 1e-4f)
    }

    @Test
    fun `output length follows the rate ratio`() {
        assertEquals(16_000, Resampler(44_100, 16_000).outputLength(44_100))
        assertEquals(96_000, Resampler(48_000, 16_000).outputLength(288_000))
        // floor(14 * 160 / 441) = 5
        assertEquals(5, Resampler(44_100, 16_000).outputLength(14))
    }

    @Test
    fun `equal rates return an independent copy`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        val output = Resampler(16_000, 16_000).process(input)

        assertArrayEquals(input, output, 0f)
        output[0] = 9f
        assertEquals(0.1f, input[0], 0f)
    }

    private fun sine(hz: Double, rate: Int, seconds: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { i -> sin(2 * PI * hz * i / rate).toFloat() }

    /** Drops 10% at each end, where the filter runs into the zero padding. */
    private fun FloatArray.interior(): FloatArray = copyOfRange(size / 10, size - size / 10)

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** Rising zero crossings per second, which is exact enough for a pure tone. */
    private fun frequencyOf(samples: FloatArray, rate: Int): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i - 1] < 0f && samples[i] >= 0f) crossings++
        }
        return crossings * rate.toDouble() / samples.size
    }

    private companion object {
        val FULL_SCALE_SINE_RMS = 1.0 / sqrt(2.0)
    }
}
