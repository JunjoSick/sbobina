package dev.junjo.eztranscribe.engine

/**
 * What every engine implements, so the router and the bench treat them identically.
 *
 * The build plan calls the result type `Result`; here it is [TranscriptionResult], so it does not
 * shadow `kotlin.Result` in every file that touches an engine.
 */
interface TranscriptionEngine {

    /** Stable id, used in the bench CSV: "android-sr", "whisper-q8", "cloud". */
    val id: String

    /**
     * Loads a model, or checks one is installed, downloading it first when needed. Timed separately
     * from [transcribe]. [onProgress] may be called from any thread.
     */
    suspend fun prepare(onProgress: (PrepareProgress) -> Unit = {})

    /**
     * [pcm] is mono float in [-1, 1] at [sampleRate]. [onProgress] may be called from any thread,
     * at most a few times a second.
     */
    suspend fun transcribe(
        pcm: FloatArray,
        sampleRate: Int,
        onProgress: (TranscriptionProgress) -> Unit = {},
    ): TranscriptionResult
}

/** What preparing an engine is doing; a first model download can take minutes. */
data class PrepareProgress(
    /** What is happening, for the screen: "Downloading the Whisper model". */
    val activity: String,
    /** 0 to 1 when the step can say how far it is, null when it cannot. */
    val fraction: Float?,
)

/** How far a transcription has got, so a long one never looks stuck. */
data class TranscriptionProgress(
    /** Share of the audio the engine has taken in, 0 to 1. */
    val fraction: Float,
    /** The transcript so far, for engines that can show it before they finish. */
    val partialText: String?,
)

data class TranscriptionResult(
    val text: String,
    val wallMs: Long,
    /** Mean token log-probability, for the cloud-escalation check. Null when an engine has none. */
    val avgLogProb: Float?,
    val noSpeechProb: Float?,
)

/** The engine cannot run on this phone right now, for a reason a person can act on. */
class EngineUnavailableException(message: String) : Exception(message)

/** The engine ran and failed. */
class TranscriptionException(message: String) : Exception(message)
