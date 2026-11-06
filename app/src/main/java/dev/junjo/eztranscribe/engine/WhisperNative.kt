package dev.junjo.eztranscribe.engine

/** The JNI surface of `whisper_jni.c`. One handle is one loaded model. */
internal object WhisperNative {

    init {
        System.loadLibrary("transcribe_whisper")
    }

    /** Returned by [transcribe] when [requestAbort] stopped it. */
    const val ABORTED = -1000

    /** Called back on the thread that called [transcribe]. */
    interface Listener {
        fun onProgress(percent: Int)

        /** One new segment, as UTF-8 bytes; Whisper can split a character across tokens. */
        fun onSegment(utf8: ByteArray)
    }

    external fun systemInfo(): String

    /** 0 when the model could not be loaded. */
    external fun load(modelPath: String): Long

    external fun free(handle: Long)

    /** Safe from any thread; [transcribe] returns [ABORTED] soon after. */
    external fun requestAbort(handle: Long)

    /** Blocks until done. 0 on success; whisper.cpp's error code or [ABORTED] otherwise. */
    external fun transcribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        language: String,
        prompt: String?,
        vadModelPath: String?,
        listener: Listener,
    ): Int

    // Results of the last transcribe() on this handle.
    external fun segmentCount(handle: Long): Int
    external fun segmentText(handle: Long, index: Int): ByteArray
    external fun segmentNoSpeechProb(handle: Long, index: Int): Float

    /** {sum of token log-probabilities, token count} over the segment's text tokens. */
    external fun segmentLogProbStats(handle: Long, index: Int): FloatArray
}
