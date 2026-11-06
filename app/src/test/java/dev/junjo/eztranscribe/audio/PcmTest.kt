package dev.junjo.eztranscribe.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {

    @Test
    fun `stereo frames average to mono`() {
        val into = FloatBuilder()
        Pcm.appendDownmixed(floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f), length = 6, channels = 2, into = into)

        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0f), into.toArray(), 0f)
    }

    @Test
    fun `only the first length samples are read, so a scratch buffer can be reused`() {
        val into = FloatBuilder()
        Pcm.appendDownmixed(floatArrayOf(0.2f, 0.4f, 9f, 9f), length = 2, channels = 1, into = into)

        assertArrayEquals(floatArrayOf(0.2f, 0.4f), into.toArray(), 0f)
    }

    @Test
    fun `successive decoder buffers append in order`() {
        val into = FloatBuilder()
        Pcm.appendDownmixed(floatArrayOf(0.1f, 0.3f), length = 2, channels = 2, into = into)
        Pcm.appendDownmixed(floatArrayOf(0.5f, 0.7f), length = 2, channels = 2, into = into)

        assertArrayEquals(floatArrayOf(0.2f, 0.6f), into.toArray(), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a buffer that splits a frame is rejected`() {
        Pcm.appendDownmixed(floatArrayOf(1f, 2f, 3f), length = 3, channels = 2, into = FloatBuilder())
    }

    @Test
    fun `builder grows past its initial capacity without losing samples`() {
        val builder = FloatBuilder(initialCapacity = 2)
        repeat(1000) { builder.add(it.toFloat()) }

        val samples = builder.toArray()
        assertEquals(1000, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(999f, samples[999], 0f)
    }

    @Test
    fun `bulk append grows past capacity in one step and keeps order`() {
        val builder = FloatBuilder(initialCapacity = 4)
        builder.add(1f)
        builder.addAll(FloatArray(10) { it + 2f }, offset = 0, length = 10)
        builder.addAll(floatArrayOf(-1f, 99f, -2f), offset = 1, length = 1)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 99f),
            builder.toArray(),
            0f,
        )
    }

    @Test
    fun `16-bit extremes map into the unit range`() {
        assertEquals(-1f, Pcm.pcm16ToFloat(Short.MIN_VALUE), 0f)
        assertEquals(32767f / 32768f, Pcm.pcm16ToFloat(Short.MAX_VALUE), 0f)
        assertEquals(0f, Pcm.pcm16ToFloat(0), 0f)
    }
}
