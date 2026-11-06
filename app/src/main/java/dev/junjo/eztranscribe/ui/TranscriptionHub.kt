package dev.junjo.eztranscribe.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import dev.junjo.eztranscribe.ForegroundWork
import dev.junjo.eztranscribe.TranscriptionService
import dev.junjo.eztranscribe.audio.AudioDecoder
import dev.junjo.eztranscribe.audio.ClipProbe
import dev.junjo.eztranscribe.audio.DecodedAudio
import dev.junjo.eztranscribe.audio.WavWriter
import dev.junjo.eztranscribe.engine.AndroidSpeechEngine
import dev.junjo.eztranscribe.engine.CloudCheckException
import dev.junjo.eztranscribe.engine.CloudEngine
import dev.junjo.eztranscribe.engine.CloudModel
import dev.junjo.eztranscribe.engine.CloudProvider
import dev.junjo.eztranscribe.engine.CloudSettingsStore
import dev.junjo.eztranscribe.engine.ModelFile
import dev.junjo.eztranscribe.engine.ModelStore
import dev.junjo.eztranscribe.engine.Models
import dev.junjo.eztranscribe.engine.TranscriptionEngine
import dev.junjo.eztranscribe.engine.WhisperEngine
import dev.junjo.eztranscribe.history.HistoryEntry
import dev.junjo.eztranscribe.history.HistoryStore
import dev.junjo.eztranscribe.share.ShareIntake
import dev.junjo.eztranscribe.share.SharedClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The engines a note can be transcribed with. Whisper q8 is "Local": on real notes it matched
 * android-sr's speed and kept the meaning where android-sr did not. Android's recognizer is the
 * fallback, both automatic (when Whisper cannot run) and on request.
 */
enum class EngineChoice(val id: String, val whisperModel: ModelFile?, val label: String) {
    WHISPER_Q8("whisper-q8", Models.WHISPER_TURBO_Q8, "Whisper"),
    CLOUD("cloud", null, "Cloud"),
    ANDROID_SR("android-sr", null, "Android's recognizer"),
}

/** A Whisper model fetched in the background while android-sr covers for it. */
data class ModelDownload(val activity: String, val fraction: Float?, val failed: Boolean = false)

/** What the cloud settings dialog shows. The key itself never enters UI state. */
data class CloudSummary(val provider: CloudProvider, val model: String, val hasKey: Boolean)

/** The models an API key offers, fetched when the key is entered or the model is changed. */
sealed interface CloudModels {
    data object Loading : CloudModels
    data class Loaded(val provider: CloudProvider, val models: List<CloudModel>) : CloudModels
    data class Failed(val message: String) : CloudModels
}

data class IntakeState(
    val clips: List<SharedClip> = emptyList(),
    val probing: Int = 0,
    /** Keyed by the clip's URI string. */
    val decodes: Map<String, DecodeState> = emptyMap(),
    /** The URI string of the clip whose decoded WAV is playing, if any. */
    val playing: String? = null,
    val engine: EngineChoice = EngineChoice.WHISPER_Q8,
    /** Keyed by [transcriptKey], so one note keeps a transcript per engine for comparison. */
    val transcripts: Map<String, TranscribeState> = emptyMap(),
    val modelDownload: ModelDownload? = null,
    val cloud: CloudSummary? = null,
    val cloudModels: CloudModels? = null,
    /** Per clip URI, the engine of the newest run, which is the version its card shows first. */
    val lastRun: Map<String, EngineChoice> = emptyMap(),
    /** Every transcript kept on the phone, newest first, including this session's. */
    val history: List<HistoryEntry> = emptyList(),
)

fun transcriptKey(clip: SharedClip, engine: EngineChoice) = "${clip.uri}#${engine.id}"

sealed interface TranscribeState {

    enum class Stage { Decoding, Preparing, Transcribing }

    /** Enough to show which stage is running and how far it has got, so it never looks stuck. */
    data class Running(
        val stage: Stage,
        /** Null while a step cannot say how far it is, such as loading a model. */
        val fraction: Float? = null,
        /** What preparing is doing right now: downloading, checking or loading a model. */
        val detail: String? = null,
        val partialText: String? = null,
        /** [SystemClock.elapsedRealtime] when transcribing began, for elapsed and remaining time. */
        val transcribingSince: Long? = null,
        val audioMs: Long? = null,
    ) : TranscribeState

    data class Done(
        val engineId: String,
        val settings: String,
        val text: String,
        val prepareMs: Long,
        val wallMs: Long,
        val audioMs: Long,
        /** Set when another engine stood in for the one chosen, saying why. */
        val note: String? = null,
    ) : TranscribeState {
        /** Processing time over audio time; below 1 is faster than real time. */
        val realTimeFactor: Double get() = wallMs.toDouble() / audioMs
    }

    data class Failed(val engineId: String, val message: String) : TranscribeState
}

sealed interface DecodeState {
    data class Running(val fraction: Float = 0f) : DecodeState
    data class Done(val summary: DecodeSummary) : DecodeState
    data class Failed(val message: String) : DecodeState
}

/** What the screen shows about a finished decode; the samples themselves stay out of UI state. */
data class DecodeSummary(
    val sampleRate: Int,
    val sourceRate: Int,
    val sourceChannels: Int,
    val codecMime: String,
    val durationMs: Long,
    val peak: Float,
    val decodeMs: Long,
    val resampleMs: Long,
    val wavFile: File,
)

/**
 * Owns every note, decode and transcription for the life of the process rather than of a screen.
 * A transcription keeps going when the screen goes away, and any screen opened later, from the
 * launcher or from the progress notification, shows the same notes.
 *
 * Holding state costs nothing; the battery cost is the work itself. The foreground service that
 * stops Android freezing the app in the background runs only while a transcription, or a model
 * download one asked for, is under way, and stops the moment the last one ends.
 */
class TranscriptionHub private constructor(private val application: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Screens currently on display; a finished transcription notifies only when this is 0. */
    private var visibleScreens = 0

    private val probe = ClipProbe(application)
    private val decoder = AudioDecoder(application)
    private val cloudStore = CloudSettingsStore(application)
    private var modelDownloadJob: Job? = null
    private val historyStore = HistoryStore(application)
    private val historyLock = Mutex()

    /** Decoded WAVs for playback: a cache, emptied at each start, since the audio is private. */
    private val decodedDir = File(application.cacheDir, "decoded")

    /** Decoded PCM, kept out of UI state; three minutes of 16 kHz audio is about 11 MB. */
    private val decodedAudio = mutableMapOf<String, DecodedAudio>()
    private val _state = MutableStateFlow(IntakeState())
    val state: StateFlow<IntakeState> = _state.asStateFlow()

    /** Keeps a rotation, which replays the launch intent, from probing the same clip twice. */
    private val handled = mutableSetOf<String>()
    private var player: MediaPlayer? = null

    /**
     * How fast android-sr is fed. It is a live-audio recognizer: fed much faster than real time it
     * silently skips speech, so the default stays at the fastest speed measured to be complete.
     */
    private var feedSpeed = DEFAULT_FEED_SPEED

    /** Whisper threads: one per fast core. On a 3-minute note, 6 beat 4 by 13% (q5). */
    private var whisperThreads = DEFAULT_WHISPER_THREADS
    private val debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        publishCloudSummary()
        scope.launch {
            val history = historyStore.load()
            _state.update { it.copy(history = (it.history + history).distinctBy { e -> e.id }) }
            withContext(Dispatchers.IO) {
                decodedDir.deleteRecursively()
                // Earlier versions kept loose copies here; the history file is now the only copy.
                File(application.filesDir, "transcripts").deleteRecursively()
                File(application.filesDir, "decoded").deleteRecursively()
                val freed = ModelStore(application).removeUnused()
                if (freed > 0) Log.i(TAG, "removed unused model files: ${freed / 1_000_000} MB")
            }
        }
        // The service follows the work: it starts with the first job and stops with the last.
        scope.launch {
            state.map(::foregroundWork).distinctUntilChanged().collect { work ->
                TranscriptionService.sync(application, work, announceWhenDone = visibleScreens == 0)
            }
        }
    }

    fun screenStarted() {
        visibleScreens++
    }

    fun screenStopped() {
        visibleScreens--
    }

    /** What the progress notification says, or null when nothing needs the app kept awake. */
    private fun foregroundWork(state: IntakeState): ForegroundWork? {
        state.transcripts.entries.firstOrNull { it.value is TranscribeState.Running }?.let { (key, value) ->
            val running = value as TranscribeState.Running
            val engineId = key.substringAfterLast('#')
            val engine = EngineChoice.entries.firstOrNull { it.id == engineId }?.label ?: engineId
            val stage = when (running.stage) {
                TranscribeState.Stage.Decoding -> "Reading the audio"
                TranscribeState.Stage.Preparing -> running.detail ?: "Preparing"
                TranscribeState.Stage.Transcribing -> "Transcribing"
            }
            return ForegroundWork("Transcribing a voice note", "$engine · $stage", running.fraction?.let { (it * 100).roundToInt() })
        }
        val download = state.modelDownload
        if (download != null && !download.failed) {
            return ForegroundWork("Downloading the Whisper model", download.activity, download.fraction?.let { (it * 100).roundToInt() })
        }
        return null
    }

    fun onIntent(intent: Intent?) {
        // Lets the bench script choose engines and settings without a rebuild; release builds ignore it.
        if (debuggable) {
            val extras = intent?.extras
            // `am start` sends --ef as a float, --ed as a double and --ei as an int; accept any.
            @Suppress("DEPRECATION")
            (extras?.get(EXTRA_FEED_SPEED) as? Number)?.let { feedSpeed = it.toDouble() }
            @Suppress("DEPRECATION")
            (extras?.get(EXTRA_THREADS) as? Number)?.let { whisperThreads = it.toInt() }
            extras?.getString(EXTRA_ENGINE)?.let { id ->
                EngineChoice.entries.firstOrNull { it.id == id }?.let(::selectEngine)
            }
        }
        val fresh = ShareIntake.urisFrom(intent).filter { handled.add(it.toString()) }
        if (fresh.isEmpty()) return

        _state.update { it.copy(probing = it.probing + fresh.size) }
        scope.launch {
            for (uri in fresh) {
                val clip = probe.probe(uri, intent?.type)
                _state.update { it.copy(clips = it.clips + clip, probing = it.probing - 1) }
            }
        }
    }

    fun decode(clip: SharedClip) {
        if (_state.value.decodes[clip.uri.toString()] is DecodeState.Running) return
        scope.launch { decodeAndKeep(clip) {} }
    }

    fun selectEngine(choice: EngineChoice) {
        _state.update { it.copy(engine = choice) }
    }

    /**
     * Lists the transcription models [key] can use, which also checks the key. A null [key] uses
     * the saved one, to change the model without typing the key again.
     */
    fun findCloudModels(key: String?) {
        val apiKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: cloudStore.load().apiKey ?: return
        _state.update { it.copy(cloudModels = CloudModels.Loading) }
        scope.launch {
            val result = try {
                val (provider, models) = CloudEngine.listModels(apiKey)
                CloudModels.Loaded(provider, models)
            } catch (e: CloudCheckException) {
                CloudModels.Failed(e.message ?: "the key could not be checked")
            }
            _state.update { it.copy(cloudModels = result) }
        }
    }

    /** A null [apiKey] keeps the saved key. */
    fun saveCloudSettings(provider: CloudProvider, model: String, apiKey: String?) {
        cloudStore.save(provider, model, apiKey)
        _state.update { it.copy(cloudModels = null) }
        publishCloudSummary()
    }

    fun removeCloudKey() {
        cloudStore.clear()
        _state.update { it.copy(cloudModels = null) }
        publishCloudSummary()
    }

    fun dismissCloudModels() {
        _state.update { it.copy(cloudModels = null) }
    }

    private fun publishCloudSummary() {
        val settings = cloudStore.load()
        _state.update { it.copy(cloud = CloudSummary(settings.provider, settings.model, !settings.apiKey.isNullOrBlank())) }
    }

    /**
     * One tap: decodes first when needed, then transcribes, reporting progress throughout. The
     * result is filed under the chosen engine even when android-sr stood in for Whisper.
     */
    fun transcribe(clip: SharedClip, choice: EngineChoice = _state.value.engine) {
        val key = transcriptKey(clip, choice)
        if (_state.value.transcripts[key] is TranscribeState.Running) return

        _state.update { it.copy(lastRun = it.lastRun + (clip.uri.toString() to choice)) }
        setTranscript(key, TranscribeState.Running(TranscribeState.Stage.Decoding, fraction = 0f))
        scope.launch {
            val outcome = try {
                val audio = decodedAudio[clip.uri.toString()] ?: decodeAndKeep(clip) { fraction ->
                    updateRunning(key) { it.copy(fraction = fraction) }
                }
                if (audio == null) {
                    val reason = (_state.value.decodes[clip.uri.toString()] as? DecodeState.Failed)?.message ?: "unknown error"
                    TranscribeState.Failed(choice.id, "could not decode the clip: $reason")
                } else {
                    transcribeWithFallback(clip, choice, audio, key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranscribeState.Failed(choice.id, describe(e))
            }
            setTranscript(key, outcome)
        }
    }

    /**
     * Whisper first, android-sr when Whisper cannot run. A missing model is downloaded in the
     * background for next time rather than making this note wait for 874 MB.
     */
    private suspend fun transcribeWithFallback(
        clip: SharedClip,
        choice: EngineChoice,
        audio: DecodedAudio,
        key: String,
    ): TranscribeState.Done {
        val model = choice.whisperModel ?: return runEngine(clip, choice, audio, key, note = null)
        val app = application

        if (!WhisperEngine.isReady(app, model)) {
            downloadInBackground(model)
            return runEngine(
                clip, EngineChoice.ANDROID_SR, audio, key,
                note = "Whisper's model is still downloading, so android-sr transcribed this note.",
            )
        }
        return try {
            runEngine(clip, choice, audio, key, note = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${choice.id} failed; falling back to android-sr", e)
            runEngine(
                clip, EngineChoice.ANDROID_SR, audio, key,
                note = "Whisper failed (${describe(e)}), so android-sr transcribed this note.",
            )
        }
    }

    /** Prepares and runs one engine, reporting into the transcript at [key]. */
    private suspend fun runEngine(
        clip: SharedClip,
        choice: EngineChoice,
        audio: DecodedAudio,
        key: String,
        note: String?,
    ): TranscribeState.Done {
        val (engine, settings) = createEngine(choice)
        setTranscript(key, TranscribeState.Running(TranscribeState.Stage.Preparing, audioMs = audio.durationMs))
        val prepareStart = System.nanoTime()
        engine.prepare { progress ->
            updateRunning(key) { it.copy(detail = progress.activity, fraction = progress.fraction) }
        }
        val prepareMs = (System.nanoTime() - prepareStart) / 1_000_000

        setTranscript(
            key,
            TranscribeState.Running(
                stage = TranscribeState.Stage.Transcribing,
                fraction = 0f,
                transcribingSince = SystemClock.elapsedRealtime(),
                audioMs = audio.durationMs,
            ),
        )
        val result = engine.transcribe(audio.samples, audio.sampleRate) { progress ->
            updateRunning(key) { it.copy(fraction = progress.fraction, partialText = progress.partialText) }
        }
        return TranscribeState.Done(
            engineId = engine.id,
            settings = settings,
            text = result.text,
            prepareMs = prepareMs,
            wallMs = result.wallMs,
            audioMs = audio.durationMs,
            note = note,
        ).also { done ->
            addToHistory(clip, choice, done)
            // One line per result, for bench scripts: readable whatever the screen shows.
            Log.i(
                TAG,
                "done engine=${done.engineId} audio_ms=${done.audioMs} prepare_ms=${done.prepareMs} " +
                    "wall_ms=${done.wallMs} rtf=${"%.3f".format(Locale.ROOT, done.realTimeFactor)} " +
                    "words=${done.text.split(' ').count { it.isNotBlank() }} " +
                    "avg_logprob=${result.avgLogProb} no_speech_prob=${result.noSpeechProb} " +
                    "settings=\"${done.settings}\" fallback=${note != null} clip=${clip.displayName}",
            )
        }
    }

    /** Fetches [model] and the voice-detection model, once, showing progress in a banner. */
    private fun downloadInBackground(model: ModelFile) {
        if (modelDownloadJob?.isActive == true) return
        val store = ModelStore(application)
        modelDownloadJob = scope.launch {
            try {
                for (file in listOf(model, Models.SILERO_VAD)) {
                    store.ensure(file) { progress ->
                        _state.update { it.copy(modelDownload = ModelDownload(progress.activity, progress.fraction)) }
                    }
                }
                _state.update { it.copy(modelDownload = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "background download of ${model.fileName} failed", e)
                _state.update {
                    it.copy(modelDownload = ModelDownload("Whisper model download failed: ${describe(e)}", null, failed = true))
                }
            }
        }
    }

    fun play(clip: SharedClip) {
        val key = clip.uri.toString()
        val done = _state.value.decodes[key] as? DecodeState.Done ?: return
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                // Declared as speech media, so it follows the media volume and ducks correctly.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(done.summary.wavFile.path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            _state.update { it.copy(playing = key) }
        } catch (e: Exception) {
            stopPlayback()
            setDecode(key, DecodeState.Failed("playback: ${describe(e)}"))
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        _state.update { it.copy(playing = null) }
    }

    /** A fresh engine for [choice], plus a line saying how it is set up, for the result. */
    private fun createEngine(choice: EngineChoice): Pair<TranscriptionEngine, String> {
        val app = application
        return when (choice) {
            EngineChoice.ANDROID_SR ->
                AndroidSpeechEngine(app, feedSpeed) to "fed at ${feedSpeed}× real time"
            EngineChoice.WHISPER_Q8 ->
                WhisperEngine(app, Models.WHISPER_TURBO_Q8, choice.id, whisperThreads) to whisperSettings()
            EngineChoice.CLOUD -> {
                val settings = cloudStore.load()
                CloudEngine(settings) to "${settings.provider.label}, ${settings.model}"
            }
        }
    }

    private fun whisperSettings() = "$whisperThreads threads, greedy, voice detection on"

    /** Decodes [clip] and keeps its audio, or publishes why not and returns null. */
    private suspend fun decodeAndKeep(clip: SharedClip, onProgress: (Float) -> Unit): DecodedAudio? {
        val key = clip.uri.toString()
        setDecode(key, DecodeState.Running())
        return try {
            val audio = decoder.decode(clip.uri) { fraction ->
                setDecode(key, DecodeState.Running(fraction))
                onProgress(fraction)
            }
            val wav = writeWav(clip, audio)
            decodedAudio[key] = audio
            setDecode(key, DecodeState.Done(summarise(audio, wav)))
            audio
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setDecode(key, DecodeState.Failed(describe(e)))
            null
        }
    }

    private fun setDecode(key: String, decodeState: DecodeState) {
        _state.update { it.copy(decodes = it.decodes + (key to decodeState)) }
    }

    private fun setTranscript(key: String, transcribeState: TranscribeState) {
        _state.update { it.copy(transcripts = it.transcripts + (key to transcribeState)) }
    }

    /**
     * Applies a progress update only while the transcription is still running. Engines report from
     * their own threads, and a late report must never overwrite a finished or failed result.
     */
    private fun updateRunning(key: String, transform: (TranscribeState.Running) -> TranscribeState.Running) {
        _state.update { state ->
            val running = state.transcripts[key] as? TranscribeState.Running ?: return@update state
            state.copy(transcripts = state.transcripts + (key to transform(running)))
        }
    }

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun addToHistory(clip: SharedClip, choice: EngineChoice, done: TranscribeState.Done) {
        val label = when (done.engineId) {
            EngineChoice.CLOUD.id -> "Cloud · " + done.settings.replace(", ", " · ")
            else -> EngineChoice.entries.firstOrNull { it.id == done.engineId }?.label ?: done.engineId
        }
        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            fileName = clip.displayName,
            durationMs = done.audioMs,
            createdAt = System.currentTimeMillis(),
            engineId = done.engineId,
            engineLabel = label,
            text = done.text,
            note = done.note,
        )
        _state.update { it.copy(history = listOf(entry) + it.history) }
        persistHistory()
    }

    fun deleteFromHistory(id: String) {
        _state.update { state -> state.copy(history = state.history.filterNot { it.id == id }) }
        persistHistory()
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        persistHistory()
    }

    /** Writes whatever the history is when the lock comes free, so saves never land out of order. */
    private fun persistHistory() {
        scope.launch {
            historyLock.withLock { historyStore.save(_state.value.history) }
        }
    }

    private fun safeBaseName(clip: SharedClip): String =
        clip.displayName.substringBeforeLast('.').replace(UNSAFE_FILENAME_CHARS, "_")

    private suspend fun writeWav(clip: SharedClip, audio: DecodedAudio): File =
        withContext(Dispatchers.IO) {
            decodedDir.mkdirs()
            File(decodedDir, "${safeBaseName(clip)}.16k.wav").also { file ->
                file.outputStream().buffered().use { WavWriter.writePcm16Mono(audio.samples, audio.sampleRate, it) }
            }
        }

    private fun summarise(audio: DecodedAudio, wav: File) = DecodeSummary(
        sampleRate = audio.sampleRate,
        sourceRate = audio.sourceRate,
        sourceChannels = audio.sourceChannels,
        codecMime = audio.codecMime,
        durationMs = audio.durationMs,
        peak = audio.peak,
        decodeMs = audio.decodeMs,
        resampleMs = audio.resampleMs,
        wavFile = wav,
    )

    companion object {
        @Volatile
        private var instance: TranscriptionHub? = null

        fun get(context: Context): TranscriptionHub = instance ?: synchronized(this) {
            instance ?: TranscriptionHub(context.applicationContext as Application).also { instance = it }
        }

        // Debug builds only, for the bench scripts: override settings for the clip the intent carries.
        const val EXTRA_FEED_SPEED = "dev.junjo.eztranscribe.FEED_SPEED"
        const val EXTRA_THREADS = "dev.junjo.eztranscribe.THREADS"
        const val EXTRA_ENGINE = "dev.junjo.eztranscribe.ENGINE"

        /** The Tensor G5 has 1 + 5 fast cores and 2 slow ones; a slow core would hold the others up. */
        const val DEFAULT_WHISPER_THREADS = 6

        /**
         * Measured on a 3-minute Opus note: 1×, 2× and 3× give word-identical transcripts, 4× drops a
         * phrase, unpaced (about 6×) drops whole sentences. 2× keeps a twofold margin below the first
         * drop, because a hot or busy phone recognises more slowly and the failure is silent.
         */
        const val DEFAULT_FEED_SPEED = 2.0

        private const val TAG = "Transcription"
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
