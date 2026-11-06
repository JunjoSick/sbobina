package dev.junjo.eztranscribe.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.junjo.eztranscribe.audio.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android's on-device recognizer, fed a decoded clip through a pipe instead of the microphone.
 *
 * A plain recognition session behaves like dictation and ends at the first pause. This one is a
 * segmented session bound to the audio source, so the recognizer keeps going across pauses,
 * reports one result per segment, and ends only when the pipe closes.
 *
 * The recognizer exposes no log-probabilities, so [TranscriptionResult.avgLogProb] and
 * [TranscriptionResult.noSpeechProb] are always null for this engine.
 */
class AndroidSpeechEngine(
    private val context: Context,
    /**
     * Audio seconds fed per wall-clock second; 0 feeds as fast as the pipe accepts. This is a
     * live-audio recognizer: fed much faster than it can listen, it skips speech without an error.
     */
    private val feedSpeed: Double,
) : TranscriptionEngine {

    override val id = "android-sr"

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun prepare(onProgress: (PrepareProgress) -> Unit) {
        onProgress(PrepareProgress("Checking the Italian speech model", null))
        withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                throw EngineUnavailableException("this phone has no on-device speech recognizer")
            }
            val support = recognitionSupport()
            when {
                support.installedOnDeviceLanguages.matchesItalian() -> Unit
                support.pendingOnDeviceLanguages.matchesItalian() ->
                    throw EngineUnavailableException("the Italian speech model is still downloading")
                support.supportedOnDeviceLanguages.matchesItalian() -> downloadItalianModel(onProgress)
                else -> throw EngineUnavailableException("the on-device recognizer does not support Italian")
            }
        }
    }

    override suspend fun transcribe(
        pcm: FloatArray,
        sampleRate: Int,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult = coroutineScope {
        val started = System.nanoTime()
        val progress = ProgressReporter(total = pcm.size, sink = onProgress)
        val (source, sink) = ParcelFileDescriptor.createPipe()
        // The feeder blocks whenever the pipe is full, so it gets its own thread.
        val feeder = launch(Dispatchers.IO) { feed(pcm, sampleRate, sink, progress) }
        val segments = try {
            withContext(Dispatchers.Main) { recognize(source, sampleRate, progress) }
        } finally {
            // Closing our copy of the read end lets a feeder still blocked on a recognizer that
            // stopped reading fail with EPIPE instead of hanging.
            source.close()
            if (withTimeoutOrNull(FEEDER_GRACE_MS) { feeder.join() } == null) {
                Log.w(TAG, "feeder still blocked after the session ended")
                feeder.cancel()
            }
        }
        TranscriptionResult(
            text = segments.joinToString(" "),
            wallMs = (System.nanoTime() - started) / 1_000_000,
            avgLogProb = null,
            noSpeechProb = null,
        )
    }

    /** Writes the clip as little-endian 16-bit PCM, then closes the pipe so the session can end. */
    private fun feed(pcm: FloatArray, sampleRate: Int, sink: ParcelFileDescriptor, progress: ProgressReporter) {
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            val chunk = ByteBuffer.allocate(CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            val started = System.nanoTime()
            var index = 0
            try {
                while (index < pcm.size) {
                    chunk.clear()
                    val end = minOf(index + CHUNK_SAMPLES, pcm.size)
                    while (index < end) chunk.putShort(WavWriter.toPcm16(pcm[index++]))
                    out.write(chunk.array(), 0, chunk.position())
                    progress.fed(index)

                    if (feedSpeed > 0) {
                        val dueNanos = (index * 1_000_000_000.0 / sampleRate / feedSpeed).toLong()
                        val aheadNanos = dueNanos - (System.nanoTime() - started)
                        if (aheadNanos > 0) Thread.sleep(aheadNanos / 1_000_000, (aheadNanos % 1_000_000).toInt())
                    }
                }
            } catch (e: IOException) {
                // The recognizer stopped reading early; recognize() reports why.
                Log.d(TAG, "feeder stopped after $index of ${pcm.size} samples", e)
            }
        }
    }

    /** Runs one segmented session on the main thread and returns its segments in order. */
    private suspend fun recognize(
        source: ParcelFileDescriptor,
        sampleRate: Int,
        progress: ProgressReporter,
    ): List<String> =
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val segments = mutableListOf<String>()
            var finished = false
            val sessionStart = System.nanoTime()
            fun trace(event: String) {
                Log.d(TAG, "+${(System.nanoTime() - sessionStart) / 1_000_000} ms $event")
            }

            fun finish(outcome: Result<List<String>>) {
                if (finished) return
                finished = true
                recognizer.destroy()
                if (continuation.isActive) continuation.resumeWith(outcome)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) {
                    trace("segment: ${segmentResults.firstResult()}")
                    segmentResults.firstResult()?.let(segments::add)
                    progress.heard(segments.joinToString(" "), committed = true)
                }

                override fun onEndOfSegmentedSession() {
                    trace("end of segmented session")
                    finish(Result.success(segments.toList()))
                }

                // A recognizer that ignores the segmented-session extra ends a plain session here.
                override fun onResults(results: Bundle) {
                    trace("results: ${results.firstResult()}")
                    results.firstResult()?.let(segments::add)
                    finish(Result.success(segments.toList()))
                }

                override fun onError(error: Int) {
                    trace("error ${errorName(error)}")
                    // These two mean "no (more) speech": a note that is silent, or one that ends in a
                    // long silence after real segments. Keep what was recognised; failing would
                    // throw away a whole transcript over its trailing silence.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        finish(Result.success(segments.toList()))
                        return
                    }
                    finish(
                        Result.failure(
                            TranscriptionException(
                                "${errorName(error)} after ${segments.size} segment(s)" +
                                    segments.lastOrNull()?.let { ", the last ending \"…${it.takeLast(40)}\"" }.orEmpty(),
                            ),
                        ),
                    )
                }

                override fun onReadyForSpeech(params: Bundle) = trace("ready for speech")
                override fun onBeginningOfSpeech() = trace("beginning of speech")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray) = Unit
                override fun onEndOfSpeech() = trace("end of speech")
                // Not traced: a partial arrives every ~100 ms and floods the log buffer on long notes.
                override fun onPartialResults(partialResults: Bundle) {
                    val partial = partialResults.firstResult() ?: return
                    progress.heard((segments + partial).joinToString(" "), committed = false)
                }
                override fun onEvent(eventType: Int, params: Bundle) = trace("event $eventType")
            })

            continuation.invokeOnCancellation {
                mainHandler.post {
                    if (!finished) {
                        finished = true
                        recognizer.cancel()
                        recognizer.destroy()
                    }
                }
            }
            recognizer.startListening(intent(source, sampleRate))
        }

    /**
     * Downloads the Italian model and returns once it is installed, or throws saying why not.
     *
     * Uses the API 34 overload with a listener. The original listener-less form is not just
     * silent: on Android 17 the system server throws a NullPointerException trying to report a
     * download error to the absent listener, and the request is lost.
     */
    private suspend fun downloadItalianModel(report: (PrepareProgress) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.triggerModelDownload(intent(source = null, sampleRate = 0))
            // The call is asynchronous; destroying the recognizer at once can cut it off.
            mainHandler.postDelayed({ recognizer.destroy() }, LEGACY_DOWNLOAD_GRACE_MS)
            throw EngineUnavailableException(
                "the Italian speech model was not installed; a download has been requested",
            )
        }

        var percent = 0
        val outcome = withTimeoutOrNull(DOWNLOAD_WAIT_MS) {
            suspendCancellableCoroutine<DownloadOutcome> { continuation ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                var closed = false
                fun close(result: DownloadOutcome?) {
                    if (closed) return
                    closed = true
                    recognizer.destroy()
                    if (result != null && continuation.isActive) continuation.resume(result)
                }
                recognizer.triggerModelDownload(
                    intent(source = null, sampleRate = 0),
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            percent = completedPercent
                            report(PrepareProgress("Downloading the Italian speech model", completedPercent / 100f))
                        }

                        override fun onSuccess() = close(DownloadOutcome.Installed)
                        override fun onScheduled() = close(DownloadOutcome.Scheduled)
                        override fun onError(error: Int) = close(DownloadOutcome.Failed(error))
                    },
                )
                continuation.invokeOnCancellation { mainHandler.post { close(null) } }
            }
        }

        when (outcome) {
            DownloadOutcome.Installed -> Log.i(TAG, "Italian speech model installed")
            DownloadOutcome.Scheduled -> throw EngineUnavailableException(
                "the Italian speech model download is scheduled; the system fetches it later, usually on Wi-Fi",
            )
            is DownloadOutcome.Failed -> throw EngineUnavailableException(
                "the Italian speech model could not be downloaded: ${errorName(outcome.error)}",
            )
            null -> throw EngineUnavailableException("the Italian speech model is still downloading ($percent%)")
        }
    }

    /**
     * Merges the feeder's position, reported from its own thread, with the recognizer's text,
     * reported on the main thread, into progress updates at most every [MIN_INTERVAL_NANOS].
     * Committed segments always go through, so finished text is never held back.
     */
    private class ProgressReporter(
        private val total: Int,
        private val sink: (TranscriptionProgress) -> Unit,
    ) {
        private var fed = 0
        private var text: String? = null
        private var lastReport = 0L

        @Synchronized
        fun fed(samples: Int) {
            fed = samples
            report(force = samples >= total)
        }

        @Synchronized
        fun heard(textSoFar: String, committed: Boolean) {
            text = textSoFar
            report(force = committed)
        }

        private fun report(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastReport < MIN_INTERVAL_NANOS) return
            lastReport = now
            sink(TranscriptionProgress(if (total == 0) 1f else fed.toFloat() / total, text))
        }

        private companion object {
            const val MIN_INTERVAL_NANOS = 250_000_000L
        }
    }

    private sealed interface DownloadOutcome {
        data object Installed : DownloadOutcome
        data object Scheduled : DownloadOutcome
        data class Failed(val error: Int) : DownloadOutcome
    }

    private suspend fun recognitionSupport(): RecognitionSupport =
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.checkRecognitionSupport(
                intent(source = null, sampleRate = 0),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        recognizer.destroy()
                        if (continuation.isActive) continuation.resume(recognitionSupport)
                    }

                    override fun onError(error: Int) {
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                EngineUnavailableException("could not ask the recognizer about Italian: ${errorName(error)}"),
                            )
                        }
                    }
                },
            )
        }

    private fun intent(source: ParcelFileDescriptor?, sampleRate: Int) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (source != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                // End the session when the pipe closes, not at the first pause.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                // Punctuation and capitalisation, the plan's expected weak spot for this engine.
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // Android masks swear words by default ("c****"); a transcript should say what was said.
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }
        }

    /** Segments after the first arrive with a leading space; trimmed so joining adds exactly one. */
    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun List<String>.matchesItalian() =
        any { it.equals(LANGUAGE, ignoreCase = true) || it.substringBefore('-').equals("it", ignoreCase = true) }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "error $error"
    }

    private companion object {
        const val TAG = "AndroidSpeechEngine"
        const val LANGUAGE = "it-IT"

        /** 100 ms of 16 kHz audio per write. */
        const val CHUNK_SAMPLES = 1600
        const val FEEDER_GRACE_MS = 2_000L

        /** Long enough for a language pack over Wi-Fi; past it, prepare() reports the percentage. */
        const val DOWNLOAD_WAIT_MS = 180_000L
        const val LEGACY_DOWNLOAD_GRACE_MS = 5_000L
    }
}
