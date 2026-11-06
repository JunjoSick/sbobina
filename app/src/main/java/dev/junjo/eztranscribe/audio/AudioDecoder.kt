package dev.junjo.eztranscribe.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.abs

/** Mono float PCM at [sampleRate], plus what it took to get there. */
class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    /** The decoder's own output rate, before resampling. Opus always decodes at 48 kHz. */
    val sourceRate: Int,
    val sourceChannels: Int,
    val codecMime: String,
    val decodeMs: Long,
    val resampleMs: Long,
) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
    val peak: Float = samples.maxOfOrNull { abs(it) } ?: 0f
}

class DecodeException(message: String) : Exception(message)

/**
 * Decodes any audio track MediaCodec supports into mono float PCM at 16 kHz, the input every
 * engine takes. Tested against Opus, AAC in MP4 and in ADTS, and MP3.
 *
 * Android's software codecs run in a separate process, and every buffer handed across costs about
 * 0.75 ms. A 3-minute Opus note is 9,201 frames, so handing them over one at a time took 11 of the
 * 17 seconds its decode needed. From Android 15, a codec that advertises multiple-frame support
 * takes whole batches per buffer instead. Older systems, codecs without the feature, and any batch
 * that fails fall back to one frame at a time.
 */
class AudioDecoder(private val context: Context) {

    /** [onProgress] gets the share of the file read so far, from a background thread. */
    suspend fun decode(
        uri: Uri,
        targetRate: Int = WHISPER_SAMPLE_RATE,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio =
        withContext(Dispatchers.IO) {
            val decodeStart = System.nanoTime()
            val pcm = decodeToMono(uri, onProgress)
            val decodeMs = millisSince(decodeStart)

            val resampleStart = System.nanoTime()
            val samples = Resampler(pcm.rate, targetRate).process(pcm.samples)
            val resampleMs = millisSince(resampleStart)

            DecodedAudio(
                samples = samples,
                sampleRate = targetRate,
                sourceRate = pcm.rate,
                sourceChannels = pcm.channels,
                codecMime = pcm.mime,
                decodeMs = decodeMs,
                resampleMs = resampleMs,
            )
        }

    private suspend fun decodeToMono(uri: Uri, onProgress: (Float) -> Unit): MonoPcm {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                decodeBatched(uri, onProgress)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "batched decode failed; decoding frame by frame instead", e)
            }
        }
        return decodeSequential(uri, onProgress)
    }

    /**
     * Hands the codec up to [FRAMES_PER_BATCH] frames per input buffer and takes its output in
     * batches too. Returns null, having decoded nothing, when the codec cannot batch.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun decodeBatched(uri: Uri, onProgress: (Float) -> Unit): MonoPcm? {
        val track = openTrack(uri)
        val callbackThread = HandlerThread("AudioDecoder").apply { start() }
        var codec: MediaCodec? = null
        try {
            val decoder = MediaCodec.createDecoderByType(track.mime)
            codec = decoder
            val canBatch = decoder.codecInfo.getCapabilitiesForType(track.mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames)
            if (!canBatch) return null

            track.format.apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
                // Setting these two is what switches the codec into batched output.
                setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_MAX_OUTPUT_BYTES)
                setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_THRESHOLD_OUTPUT_BYTES)
            }

            // Everything below runs on the callback thread until `finished` completes, which
            // publishes the collected samples back to this coroutine.
            val extractor = track.extractor
            val collector = PcmCollector()
            val finished = CompletableDeferred<Unit>()
            var inputDone = false
            var endQueued = false
            var frames = 0
            var inputBatches = 0
            var outputBuffers = 0
            var lastProgressNanos = 0L

            fun takeOutput(codec: MediaCodec, index: Int, infos: Collection<MediaCodec.BufferInfo>) {
                if (collector.rate == 0) collector.adopt(codec.getOutputFormat(index))
                val buffer = codec.getOutputBuffer(index)
                var end = false
                for (info in infos) {
                    if (buffer != null) collector.append(buffer, info.offset, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) end = true
                }
                codec.releaseOutputBuffer(index, false)
                outputBuffers++
                if (end) finished.complete(Unit)
            }

            decoder.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (endQueued) return
                        try {
                            if (inputDone) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                endQueued = true
                                return
                            }
                            val buffer = codec.getInputBuffer(index) ?: throw DecodeException("decoder gave no input buffer")
                            val batch = ArrayDeque<MediaCodec.BufferInfo>()
                            var offset = 0
                            while (batch.size < FRAMES_PER_BATCH) {
                                val next = extractor.sampleSize
                                if (next < 0) {
                                    inputDone = true
                                    break
                                }
                                if (offset + next > buffer.capacity()) break
                                val size = extractor.readSampleData(buffer, offset)
                                if (size < 0) {
                                    inputDone = true
                                    break
                                }
                                batch.add(MediaCodec.BufferInfo().apply { set(offset, size, extractor.sampleTime, 0) })
                                offset += size
                                extractor.advance()
                            }
                            if (batch.isEmpty() && !inputDone) {
                                // A single frame larger than the buffer. Queuing nothing here would
                                // read as the end of the stream and truncate the note silently.
                                throw DecodeException(
                                    "a ${extractor.sampleSize}-byte frame does not fit a ${buffer.capacity()}-byte buffer",
                                )
                            }
                            if (batch.isEmpty()) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                endQueued = true
                            } else {
                                codec.queueInputBuffers(index, batch)
                                frames += batch.size
                                inputBatches++
                                val now = System.nanoTime()
                                if (track.durationUs > 0 && now - lastProgressNanos > PROGRESS_INTERVAL_NANOS) {
                                    lastProgressNanos = now
                                    onProgress((batch.last().presentationTimeUs.toFloat() / track.durationUs).coerceIn(0f, 1f))
                                }
                            }
                        } catch (e: Exception) {
                            finished.completeExceptionally(e)
                        }
                    }

                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        try {
                            takeOutput(codec, index, listOf(info))
                        } catch (e: Exception) {
                            finished.completeExceptionally(e)
                        }
                    }

                    override fun onOutputBuffersAvailable(
                        codec: MediaCodec,
                        index: Int,
                        infos: ArrayDeque<MediaCodec.BufferInfo>,
                    ) {
                        try {
                            takeOutput(codec, index, infos)
                        } catch (e: Exception) {
                            finished.completeExceptionally(e)
                        }
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        try {
                            collector.adopt(format)
                        } catch (e: Exception) {
                            finished.completeExceptionally(e)
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        finished.completeExceptionally(e)
                    }
                },
                Handler(callbackThread.looper),
            )
            decoder.configure(track.format, null, null, 0)
            decoder.start()

            // Generous: decoding runs far faster than real time, so this only catches a stall.
            withTimeout(track.durationUs / 1000 + STALL_GRACE_MS) { finished.await() }

            if (collector.rate == 0) throw DecodeException("the decoder produced no audio")
            Log.d(
                TAG,
                "${track.mime} via ${decoder.name}, batched: $frames frames in $inputBatches input buffers, " +
                    "$outputBuffers output buffers, encoding ${collector.encoding}, " +
                    "${collector.rate} Hz x${collector.channels}; convert ${collector.convertNanos / 1_000_000} ms",
            )
            return MonoPcm(collector.mono.toArray(), collector.rate, collector.channels, track.mime)
        } finally {
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                decoder.release()
            }
            track.extractor.release()
            callbackThread.quitSafely()
        }
    }

    /** One frame per input buffer: works everywhere, at about 0.75 ms of overhead per frame. */
    private suspend fun decodeSequential(uri: Uri, onProgress: (Float) -> Unit): MonoPcm {
        val track = openTrack(uri)
        var codec: MediaCodec? = null
        try {
            val extractor = track.extractor
            // Ask for float output. A decoder that cannot comply falls back to 16-bit and says so
            // in its output format, which is what the collector trusts.
            track.format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

            val decoder = MediaCodec.createDecoderByType(track.mime)
            codec = decoder
            decoder.configure(track.format, null, null, 0)
            decoder.start()

            val collector = PcmCollector()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleRounds = 0
            var lastProgressNanos = 0L
            var inputs = 0
            var outputs = 0

            while (true) {
                currentCoroutineContext().ensureActive()
                var progressed = false

                // Fill every free input slot before waiting on output, so frames pipeline.
                while (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buffer = decoder.getInputBuffer(inIndex)
                        ?: throw DecodeException("decoder gave no input buffer")
                    val size = extractor.readSampleData(buffer, 0)
                    val sampleTime = extractor.sampleTime
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                        extractor.advance()

                        val now = System.nanoTime()
                        if (track.durationUs > 0 && now - lastProgressNanos > PROGRESS_INTERVAL_NANOS) {
                            lastProgressNanos = now
                            onProgress((sampleTime.toFloat() / track.durationUs).coerceIn(0f, 1f))
                        }
                    }
                    inputs++
                    progressed = true
                }

                // Block only when there was nothing to feed; otherwise go straight back to feeding.
                val outIndex = decoder.dequeueOutputBuffer(info, if (progressed) 0 else TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    collector.adopt(decoder.outputFormat)
                    progressed = true
                } else if (outIndex >= 0) {
                    outputs++
                    if (collector.rate == 0) collector.adopt(decoder.getOutputFormat(outIndex))
                    decoder.getOutputBuffer(outIndex)?.let { collector.append(it, info.offset, info.size) }
                    decoder.releaseOutputBuffer(outIndex, false)
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
                if (idleRounds > MAX_IDLE_ROUNDS) {
                    throw DecodeException("decoder stalled after ${collector.mono.size} samples")
                }
            }

            if (collector.rate == 0) throw DecodeException("the decoder produced no audio")
            Log.d(
                TAG,
                "${track.mime} via ${decoder.name}, frame by frame: $inputs inputs, $outputs outputs, " +
                    "encoding ${collector.encoding}, ${collector.rate} Hz x${collector.channels}; " +
                    "convert ${collector.convertNanos / 1_000_000} ms",
            )
            return MonoPcm(collector.mono.toArray(), collector.rate, collector.channels, track.mime)
        } finally {
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                decoder.release()
            }
            track.extractor.release()
        }
    }

    /** Opens [uri] and selects its first audio track. The caller releases the extractor. */
    private fun openTrack(uri: Uri): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("no audio track in the file")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw DecodeException("the audio track names no codec")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            return Track(extractor, format, mime, durationUs)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    private class Track(
        val extractor: MediaExtractor,
        val format: MediaFormat,
        val mime: String,
        val durationUs: Long,
    )

    /**
     * Turns decoder output into mono float samples, whatever encoding the decoder chose. Shared by
     * both decode paths, so they cannot drift apart. Not thread-safe: one decode, one thread.
     */
    private class PcmCollector {
        val mono = FloatBuilder()

        // Taken from the decoder's output format, never the extractor's: HE-AAC reports half its
        // real rate until SBR is decoded, and Opus decodes at 48 kHz whatever the file says.
        var rate = 0
            private set
        var channels = 0
            private set
        var encoding = AudioFormat.ENCODING_PCM_16BIT
            private set
        var convertNanos = 0L
            private set

        private var floats = FloatArray(0)
        private var shorts = ShortArray(0)

        fun adopt(format: MediaFormat) {
            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (mono.size > 0 && newRate != rate) {
                throw DecodeException("sample rate changed mid-stream, $rate to $newRate Hz")
            }
            rate = newRate
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        }

        fun append(buffer: ByteBuffer, offset: Int, size: Int) {
            if (size <= 0) return
            val started = System.nanoTime()
            // Limit before position: the other order throws when the old limit sits below the
            // new offset.
            buffer.limit(offset + size)
            buffer.position(offset)
            val bytes = buffer.order(ByteOrder.nativeOrder())

            val count: Int
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val view = bytes.asFloatBuffer()
                    count = view.remaining()
                    if (floats.size < count) floats = FloatArray(count)
                    view.get(floats, 0, count)
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    // One bulk copy, then a loop over a plain array: reading the ShortBuffer
                    // sample by sample cost 1.5 s on a 3-minute note.
                    val view = bytes.asShortBuffer()
                    count = view.remaining()
                    if (shorts.size < count) shorts = ShortArray(count)
                    view.get(shorts, 0, count)
                    if (floats.size < count) floats = FloatArray(count)
                    for (i in 0 until count) floats[i] = Pcm.pcm16ToFloat(shorts[i])
                }
                else -> throw DecodeException("unsupported PCM encoding $encoding")
            }
            Pcm.appendDownmixed(floats, count, channels, mono)
            convertNanos += System.nanoTime() - started
        }
    }

    private class MonoPcm(val samples: FloatArray, val rate: Int, val channels: Int, val mime: String)

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        const val WHISPER_SAMPLE_RATE = 16_000
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L

        /** 500 empty rounds at 10 ms each: five seconds with neither input taken nor output given. */
        private const val MAX_IDLE_ROUNDS = 500

        /** Two seconds of Opus or AAC per crossing; the input buffer size caps it lower if needed. */
        private const val FRAMES_PER_BATCH = 100

        /** Room for 100 frames of even 256 kbps AAC. */
        private const val BATCH_INPUT_BYTES = 256 * 1024
        private const val BATCH_MAX_OUTPUT_BYTES = 1024 * 1024
        private const val BATCH_THRESHOLD_OUTPUT_BYTES = 256 * 1024

        /** Added to the clip's own duration before a batched decode counts as stalled. */
        private const val STALL_GRACE_MS = 60_000L
    }
}
