package dev.junjo.eztranscribe.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A file the app downloads once and trusts only after its SHA-256 matches. */
data class ModelFile(
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    /** For the screen: "Downloading the <label>". */
    val label: String,
)

object Models {
    private const val WHISPER_CPP = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    // Sizes and SHA-256 from Hugging Face's own LFS metadata for these files. q5_0 was dropped: on
    // this chip it was slower than q8_0 (ggml has repacked int8 kernels only for q8_0).
    val WHISPER_TURBO_Q8 = ModelFile(
        fileName = "ggml-large-v3-turbo-q8_0.bin",
        url = "$WHISPER_CPP/ggml-large-v3-turbo-q8_0.bin",
        bytes = 874_188_075,
        sha256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
        label = "Whisper model (q8)",
    )
    val SILERO_VAD = ModelFile(
        fileName = "ggml-silero-v6.2.0.bin",
        url = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
        bytes = 885_098,
        sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
        label = "voice detection model",
    )

    val ALL = listOf(WHISPER_TURBO_Q8, SILERO_VAD)
}

/**
 * Keeps model files in app-private storage. Downloads resume where they stopped: large files from
 * Hugging Face's CDN were cut off mid-transfer more than once during development, and starting a
 * 874 MB download over each time would make the first run unbearable.
 */
class ModelStore(context: Context) {

    private val dir = File(context.filesDir, "models")

    /**
     * Deletes anything in the models folder that no current model owns, such as the q5 model
     * earlier versions downloaded (574 MB). Partial downloads of current models are kept.
     */
    fun removeUnused(): Long {
        val owned = Models.ALL.flatMap { listOf(it.fileName, it.fileName + ".verified", it.fileName + ".part") }.toSet()
        var freed = 0L
        dir.listFiles()?.filter { it.name !in owned }?.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /** Downloaded and verified already: [ensure] would return at once, without network or hashing. */
    fun isReady(model: ModelFile): Boolean =
        File(dir, model.fileName).length() == model.bytes && File(dir, model.fileName + ".verified").exists()

    /** The verified file, downloading and checking it first when needed. */
    suspend fun ensure(model: ModelFile, onProgress: (PrepareProgress) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, model.fileName)
        val marker = File(dir, model.fileName + ".verified")

        if (file.length() == model.bytes && marker.exists()) return@withContext file
        // Present but never checked, for example pushed by a developer: check it once.
        if (file.length() == model.bytes && sha256(file, model, onProgress) == model.sha256) {
            marker.writeText(model.sha256)
            return@withContext file
        }
        file.delete()
        marker.delete()

        val part = File(dir, model.fileName + ".part")
        download(model, part, onProgress)
        if (sha256(part, model, onProgress) != model.sha256) {
            part.delete()
            throw EngineUnavailableException("the downloaded ${model.label} was damaged; try again")
        }
        if (!part.renameTo(file)) throw IOException("could not move ${part.name} into place")
        marker.writeText(model.sha256)
        file
    }

    private suspend fun download(model: ModelFile, part: File, onProgress: (PrepareProgress) -> Unit) {
        val needed = model.bytes - part.length()
        if (dir.usableSpace < needed + FREE_SPACE_MARGIN) {
            throw EngineUnavailableException(
                "not enough free space for the ${model.label}: it needs ${needed / MB} MB, ${dir.usableSpace / MB} MB are free",
            )
        }

        // Every pass that leaves the file short counts, whether it threw or the server simply
        // closed early: a connection that keeps ending cleanly must not loop forever.
        var attempt = 0
        var lastError: IOException? = null
        while (part.length() < model.bytes) {
            currentCoroutineContext().ensureActive()
            try {
                fetchFrom(part.length(), model, part, onProgress)
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "download of ${model.fileName} stopped at ${part.length()} bytes", e)
            }
            if (part.length() >= model.bytes) break
            if (++attempt >= MAX_ATTEMPTS) {
                throw EngineUnavailableException(
                    "the ${model.label} download kept failing: ${lastError?.message ?: "the connection kept closing early"}",
                )
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /** Appends to [part] from byte [offset], or starts over when the server ignores the range. */
    private suspend fun fetchFrom(offset: Long, model: ModelFile, part: File, onProgress: (PrepareProgress) -> Unit) {
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val append = when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("HTTP ${connection.responseCode} for ${model.fileName}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = if (append) offset else 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.nanoTime()
                        if (now - lastReport > PROGRESS_INTERVAL_NANOS) {
                            lastReport = now
                            onProgress(
                                PrepareProgress(
                                    "Downloading the ${model.label}: ${written / MB} of ${model.bytes / MB} MB",
                                    written.toFloat() / model.bytes,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sha256(file: File, model: ModelFile, onProgress: (PrepareProgress) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        var lastReport = 0L
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                done += read
                val now = System.nanoTime()
                if (now - lastReport > PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    onProgress(PrepareProgress("Checking the ${model.label}", done.toFloat() / file.length()))
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ModelStore"
        const val MB = 1_000_000L
        const val BUFFER_BYTES = 1 shl 20
        const val FREE_SPACE_MARGIN = 200 * MB
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}
