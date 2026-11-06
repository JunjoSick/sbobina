package dev.junjo.eztranscribe.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp large-v3-turbo on the CPU, at the quantization [model] carries. Italian is forced,
 * a short punctuated Italian prompt steers punctuation, and Silero voice detection runs first, so
 * silence never reaches the decoder, where Whisper is known to invent text.
 */
class WhisperEngine(
    context: Context,
    private val model: ModelFile,
    override val id: String,
    private val threads: Int,
) : TranscriptionEngine {

    private val store = ModelStore(context)
    private var modelFile: File? = null
    private var vadFile: File? = null

    override suspend fun prepare(onProgress: (PrepareProgress) -> Unit) {
        val modelFile = store.ensure(model, onProgress).also { this.modelFile = it }
        vadFile = store.ensure(Models.SILERO_VAD, onProgress)
        onProgress(PrepareProgress("Loading the ${model.label}", null))
        WhisperModelCache.withModel(modelFile) { }
    }

    override suspend fun transcribe(
        pcm: FloatArray,
        sampleRate: Int,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        require(sampleRate == SAMPLE_RATE) { "Whisper takes $SAMPLE_RATE Hz audio, got $sampleRate Hz" }
        val modelFile = checkNotNull(modelFile) { "prepare() must run before transcribe()" }
        val started = System.nanoTime()
        return WhisperModelCache.withModel(modelFile) { handle ->
            run(handle, pcm, onProgress)
        }.copy(wallMs = (System.nanoTime() - started) / 1_000_000)
    }

    private suspend fun run(
        handle: Long,
        pcm: FloatArray,
        report: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult = coroutineScope {
        // Written by the native callbacks, on the one thread that runs transcribe().
        val segments = mutableListOf<String>()
        var fraction = 0f
        val listener = object : WhisperNative.Listener {
            override fun onProgress(percent: Int) {
                fraction = percent / 100f
                report(TranscriptionProgress(fraction, segments.joinToString(" ").ifEmpty { null }))
            }

            override fun onSegment(utf8: ByteArray) {
                String(utf8, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let(segments::add)
                report(TranscriptionProgress(fraction, segments.joinToString(" ").ifEmpty { null }))
            }
        }

        // The native call blocks and cannot see coroutine cancellation; this turns it into an abort.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                WhisperNative.requestAbort(handle)
            }
        }
        val result = withContext(Dispatchers.IO) {
            WhisperNative.transcribe(handle, pcm, threads, LANGUAGE, INITIAL_PROMPT, vadFile?.path, listener)
        }
        watcher.cancel()
        when (result) {
            0 -> Unit
            // Not cancelled, or withContext would already have thrown: a callback failed.
            WhisperNative.ABORTED -> throw TranscriptionException("whisper.cpp stopped after a callback failed")
            else -> throw TranscriptionException("whisper.cpp failed with code $result")
        }
        collect(handle)
    }

    /** The finished segments, read back once rather than trusted from the live callbacks. */
    private fun collect(handle: Long): TranscriptionResult {
        val count = WhisperNative.segmentCount(handle)
        val texts = mutableListOf<String>()
        var logProbSum = 0.0
        var tokens = 0.0
        var noSpeechSum = 0.0
        for (i in 0 until count) {
            String(WhisperNative.segmentText(handle, i), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let(texts::add)
            val stats = WhisperNative.segmentLogProbStats(handle, i)
            logProbSum += stats[0]
            tokens += stats[1]
            noSpeechSum += WhisperNative.segmentNoSpeechProb(handle, i)
        }
        return TranscriptionResult(
            text = texts.joinToString(" "),
            wallMs = 0,
            avgLogProb = if (tokens > 0) (logProbSum / tokens).toFloat() else null,
            noSpeechProb = if (count > 0) (noSpeechSum / count).toFloat() else null,
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val LANGUAGE = "it"

        /** Whether [model] and the voice-detection model are on the phone and verified. */
        fun isReady(context: Context, model: ModelFile): Boolean =
            ModelStore(context).let { it.isReady(model) && it.isReady(Models.SILERO_VAD) }

        /**
         * Steers punctuation and register, not content. Deliberately shares no phrasing with the
         * test fixtures, so it cannot flatter the bench.
         */
        private const val INITIAL_PROMPT =
            "Allora, ti spiego in breve: oggi pomeriggio sono fuori, ma stasera ti richiamo."
    }
}

/**
 * Holds one loaded Whisper model between transcriptions and frees it after [IDLE_MS] unused.
 * Loading 600 to 900 MB takes seconds, so back-to-back notes should not pay it twice; holding it
 * forever would keep that memory from everything else on the phone. One model at a time, so
 * switching between q5 and q8 never holds both.
 */
internal object WhisperModelCache {

    private const val IDLE_MS = 3 * 60_000L

    private class Loaded(val path: String, val handle: Long)

    private val mutex = Mutex()
    private var loaded: Loaded? = null
    private var releaseJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs [block] with the model at [file] loaded, loading it first when needed. One caller at a time. */
    suspend fun <T> withModel(file: File, block: suspend (handle: Long) -> T): T = mutex.withLock {
        releaseJob?.cancel()
        try {
            block(handleFor(file))
        } finally {
            releaseJob = scope.launch {
                delay(IDLE_MS)
                mutex.withLock { unload() }
            }
        }
    }

    private suspend fun handleFor(file: File): Long {
        loaded?.takeIf { it.path == file.path }?.let { return it.handle }
        unload()
        val handle = withContext(Dispatchers.IO) { WhisperNative.load(file.path) }
        if (handle == 0L) throw EngineUnavailableException("could not load ${file.name}; it may be damaged")
        loaded = Loaded(file.path, handle)
        return handle
    }

    private fun unload() {
        loaded?.let { WhisperNative.free(it.handle) }
        loaded = null
    }
}
