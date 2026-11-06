package dev.junjo.eztranscribe.audio

/**
 * A growable float buffer. Minutes of audio as a `List<Float>` would box every sample; this keeps
 * one primitive array and doubles it as needed.
 */
class FloatBuilder(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(maxOf(1, initialCapacity))

    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    /** Appends [length] values from [source] in one copy, growing at most once. */
    fun addAll(source: FloatArray, offset: Int, length: Int) {
        if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
        System.arraycopy(source, offset, data, size, length)
        size += length
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Pure helpers on PCM sample arrays. No Android types, so they test on the JVM. */
object Pcm {

    /**
     * Averages the first [length] interleaved samples of [interleaved] down to mono and appends
     * them to [into]. Works one decoder buffer at a time, so a long stereo clip never exists
     * whole in its interleaved form.
     */
    fun appendDownmixed(interleaved: FloatArray, length: Int, channels: Int, into: FloatBuilder) {
        require(channels >= 1) { "channel count must be positive, was $channels" }
        require(length % channels == 0) { "$length samples do not divide into $channels channels" }

        if (channels == 1) {
            into.addAll(interleaved, 0, length)
            return
        }
        var frame = 0
        while (frame < length) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame + c]
            into.add(sum / channels)
            frame += channels
        }
    }

    /** Signed 16-bit to [-1, 1). */
    fun pcm16ToFloat(sample: Short): Float = sample / 32768f
}
