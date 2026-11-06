package dev.junjo.eztranscribe.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited sample-rate conversion by Kaiser-windowed sinc.
 *
 * With integer rates the output instants fall on only `up = toRate / gcd(fromRate, toRate)`
 * distinct fractional positions between input samples, so one kernel per position is computed
 * up front: 160 kernels for 44.1 kHz to 16 kHz, a single one for 48 kHz to 16 kHz. The per-sample
 * work is then a plain dot product.
 *
 * Downsampling filters below the new Nyquist before picking samples, so a 10 kHz component in a
 * 48 kHz clip is removed rather than folded down to 6 kHz, where it would sit in the speech band.
 */
class Resampler(
    val fromRate: Int,
    val toRate: Int,
    zeroCrossings: Int = DEFAULT_ZERO_CROSSINGS,
) {
    private val up: Int
    private val down: Int

    /** Kernel index 0 lines up with input offset `1 - reach` from the output instant's base. */
    private val reach: Int
    private val taps: Int
    private val kernels: Array<FloatArray>

    init {
        require(fromRate > 0 && toRate > 0) { "rates must be positive: $fromRate -> $toRate" }
        require(zeroCrossings > 0) { "zeroCrossings must be positive, was $zeroCrossings" }

        val divisor = gcd(fromRate, toRate)
        up = toRate / divisor
        down = fromRate / divisor

        // Cutoff in cycles per input sample: the lower of the two Nyquists, pulled in slightly so
        // most of the transition band sits below it.
        val cutoff = 0.5 * minOf(1.0, toRate.toDouble() / fromRate) * PASSBAND_FRACTION
        val halfWidth = zeroCrossings / (2.0 * cutoff)
        reach = ceil(halfWidth).toInt()
        taps = 2 * reach

        kernels = Array(up) { phase ->
            val fraction = phase.toDouble() / up
            val kernel = DoubleArray(taps) { j ->
                val distance = (j - reach + 1) - fraction
                if (abs(distance) >= halfWidth) {
                    0.0
                } else {
                    sinc(2.0 * cutoff * distance) * kaiser(distance / halfWidth)
                }
            }
            // Unit DC gain per phase. This also absorbs the 2 * cutoff scale of the ideal filter.
            val sum = kernel.sum()
            FloatArray(taps) { (kernel[it] / sum).toFloat() }
        }
    }

    fun outputLength(inputLength: Int): Int = ((inputLength.toLong() * up) / down).toInt()

    fun process(input: FloatArray): FloatArray {
        if (up == down) return input.copyOf()

        val output = FloatArray(outputLength(input.size))
        for (n in output.indices) {
            val position = n.toLong() * down
            val base = (position / up).toInt()
            val kernel = kernels[(position % up).toInt()]
            val start = base - reach + 1

            var acc = 0f
            if (start >= 0 && start + taps <= input.size) {
                for (j in 0 until taps) acc += kernel[j] * input[start + j]
            } else {
                // Within one kernel width of either end: treat samples past the edge as silence.
                for (j in 0 until taps) {
                    val i = start + j
                    if (i >= 0 && i < input.size) acc += kernel[j] * input[i]
                }
            }
            output[n] = acc
        }
        return output
    }

    companion object {
        /** 16 lobes each side, about 100 taps at a 3:1 ratio. */
        const val DEFAULT_ZERO_CROSSINGS = 16

        // Cutoff and window follow ffmpeg's swresample defaults. Whisper's reference pipeline
        // resamples through ffmpeg, so this matches what its training audio went through, and
        // keeps sibilant energy up to ~7.7 kHz that a more conservative cutoff would shave off.
        private const val PASSBAND_FRACTION = 0.97
        private const val KAISER_BETA = 9.0

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double =
            besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / besselI0(KAISER_BETA)

        /** Zeroth-order modified Bessel function of the first kind, by its power series. */
        private fun besselI0(x: Double): Double {
            val half = x / 2.0
            var term = 1.0
            var sum = 1.0
            var k = 1
            while (term > sum * 1e-12) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }
    }
}
