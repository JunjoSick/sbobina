package dev.junjo.eztranscribe.engine

import android.content.Context
import android.util.Base64
import dev.junjo.eztranscribe.audio.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The transcription services the cloud engine can use. Nobody picks one: [fromKey] tells them
 * apart by the key's prefix, and the key then lists the models it can use.
 */
enum class CloudProvider(
    val label: String,
    val transcriptionUrl: String,
    val modelsUrl: String,
    /** The model offered first when it is in the list. */
    val suggestedModel: String,
) {
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1/audio/transcriptions",
        // OpenRouter lists hundreds of models; only about 30 output transcriptions.
        "https://openrouter.ai/api/v1/models?output_modalities=transcription",
        "mistralai/voxtral-mini-transcribe",
    ),
    GROQ("Groq", "https://api.groq.com/openai/v1/audio/transcriptions", "https://api.groq.com/openai/v1/models", "whisper-large-v3"),
    OPENAI("OpenAI", "https://api.openai.com/v1/audio/transcriptions", "https://api.openai.com/v1/models", "gpt-4o-transcribe"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1/audio/transcriptions", "https://api.mistral.ai/v1/models", "voxtral-mini-latest"),
    ;

    /** Whether a model from this provider's general list does transcription. */
    fun transcribes(id: String): Boolean = when (this) {
        OPENROUTER -> true // already filtered by the request
        GROQ -> "whisper" in id
        OPENAI -> "transcribe" in id || "whisper" in id
        MISTRAL -> "voxtral" in id && "realtime" !in id
    }

    companion object {
        /** OpenRouter `sk-or-`, Groq `gsk_`, OpenAI `sk-`; Mistral keys have no prefix. */
        fun fromKey(key: String): CloudProvider {
            val k = key.trim()
            return when {
                k.startsWith("sk-or-") -> OPENROUTER
                k.startsWith("gsk_") -> GROQ
                k.startsWith("sk-") -> OPENAI
                else -> MISTRAL
            }
        }
    }
}

/** What CloudSettingsStore keeps; [apiKey] never leaves encrypted storage except for a request. */
data class CloudSettings(
    val provider: CloudProvider,
    val model: String,
    val apiKey: String?,
)

/** One model a key can transcribe with. [name] is the provider's display name, when it has one. */
data class CloudModel(val id: String, val name: String)

/** Why a key could not list its models, worded for the settings screen. */
class CloudCheckException(message: String) : Exception(message)

/**
 * The paid, online option. It runs only when asked for, per note: the audio is other people's
 * voices, and it leaves the phone. Sends 16 kHz mono WAV and asks for Italian.
 */
class CloudEngine(private val settings: CloudSettings) : TranscriptionEngine {

    override val id = "cloud"

    override suspend fun prepare(onProgress: (PrepareProgress) -> Unit) {
        if (settings.apiKey.isNullOrBlank()) {
            throw EngineUnavailableException("no API key; add one in Settings")
        }
    }

    override suspend fun transcribe(
        pcm: FloatArray,
        sampleRate: Int,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val wav = ByteArrayOutputStream().also { WavWriter.writePcm16Mono(pcm, sampleRate, it) }.toByteArray()
        val (bytes, contentType) = requestBody(wav)

        val connection = (URL(settings.provider.transcriptionUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            if (settings.provider == CloudProvider.OPENROUTER) setRequestProperty("X-Title", APP_TITLE)
        }
        try {
            // Uploading is the part that can be measured; the provider's own processing cannot.
            connection.outputStream.use { out ->
                var sent = 0
                while (sent < bytes.size) {
                    val n = minOf(UPLOAD_CHUNK, bytes.size - sent)
                    out.write(bytes, sent, n)
                    sent += n
                    onProgress(TranscriptionProgress(0.9f * sent / bytes.size, null))
                }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) throw TranscriptionException(describeFailure(settings.provider, status, response))

            val text = JSONObject(response).optString("text").trim()
            TranscriptionResult(
                text = text,
                wallMs = (System.nanoTime() - started) / 1_000_000,
                avgLogProb = null,
                noSpeechProb = null,
            )
        } catch (e: IOException) {
            throw TranscriptionException("could not reach ${settings.provider.label}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * OpenRouter takes JSON with the audio in base64; the others take OpenAI's multipart upload.
     * Returns the body and its content type.
     */
    private fun requestBody(wav: ByteArray): Pair<ByteArray, String> =
        if (settings.provider == CloudProvider.OPENROUTER) {
            val json = JSONObject()
                .put("model", settings.model)
                .put("language", LANGUAGE)
                .put("input_audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav"))
            json.toString().toByteArray(Charsets.UTF_8) to "application/json"
        } else {
            val body = Multipart.build(
                boundary = "transcribe-${System.nanoTime()}",
                fields = listOf("model" to settings.model, "language" to LANGUAGE, "response_format" to "json"),
                file = Multipart.File(field = "file", name = "note.wav", contentType = "audio/wav", bytes = wav),
            )
            body.bytes to body.contentType
        }

    companion object {
        private const val LANGUAGE = "it"
        private const val APP_TITLE = "Sbobina"
        private const val CONNECT_TIMEOUT_MS = 15_000
        // Providers process a few minutes of audio in seconds, but allow for a slow queue.
        private const val READ_TIMEOUT_MS = 180_000
        private const val UPLOAD_CHUNK = 64 * 1024

        /**
         * The transcription models [key] can use, suggested one first. Asking with the key also
         * checks it: a wrong key fails here, before any note is sent.
         */
        suspend fun listModels(key: String): Pair<CloudProvider, List<CloudModel>> = withContext(Dispatchers.IO) {
            val provider = CloudProvider.fromKey(key)
            // OpenRouter's model list is public, so it would accept any key; its key endpoint does not.
            if (provider == CloudProvider.OPENROUTER) get(provider, "https://openrouter.ai/api/v1/key", key)
            val data = JSONObject(get(provider, provider.modelsUrl, key)).optJSONArray("data")
                ?: throw CloudCheckException("${provider.label} sent no model list")
            val models = (0 until data.length())
                .map { data.getJSONObject(it) }
                .map { CloudModel(it.getString("id"), it.optString("name").ifBlank { it.getString("id") }) }
                .filter { provider.transcribes(it.id) }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.id != provider.suggestedModel }, { it.name.lowercase() }))
            if (models.isEmpty()) throw CloudCheckException("${provider.label} offers this key no transcription models")
            provider to models
        }

        private fun get(provider: CloudProvider, url: String, key: String): String {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    .orEmpty()
                if (status !in 200..299) throw CloudCheckException(describeFailure(provider, status, body))
                return body
            } catch (e: IOException) {
                throw CloudCheckException("could not reach ${provider.label}: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
        }

        private fun describeFailure(provider: CloudProvider, status: Int, response: String): String {
            val detail = runCatching {
                val json = JSONObject(response)
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("detail").takeIf { it.isNotBlank() }
            }.getOrNull() ?: response.take(200)
            val what = when (status) {
                401, 403 -> "${provider.label} rejected the API key"
                402 -> "${provider.label} says the account is out of credit"
                413 -> "the note is too large for ${provider.label}"
                429 -> "${provider.label} is rate-limiting or out of credit"
                else -> "${provider.label} answered HTTP $status"
            }
            return if (detail.isBlank()) what else "$what: $detail"
        }
    }
}

/** A multipart/form-data body, built in memory: a voice note is a few MB at most. */
object Multipart {

    class File(val field: String, val name: String, val contentType: String, val bytes: ByteArray)

    class Body(val bytes: ByteArray, val contentType: String)

    fun build(boundary: String, fields: List<Pair<String, String>>, file: File): Body {
        val out = ByteArrayOutputStream()
        fun line(text: String = "") = out.write("$text\r\n".toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line()
            line(value)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.name}\"")
        line("Content-Type: ${file.contentType}")
        line()
        out.write(file.bytes)
        line()
        line("--$boundary--")
        return Body(out.toByteArray(), "multipart/form-data; boundary=$boundary")
    }
}

/**
 * Keeps the cloud settings, with the API key encrypted by an AES key that lives in the Android
 * Keystore and never leaves it. Other settings are plain preferences.
 */
class CloudSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun load(): CloudSettings {
        val provider = prefs.getString(KEY_PROVIDER, null)
            ?.let { name -> CloudProvider.entries.firstOrNull { it.name == name } }
            ?: CloudProvider.MISTRAL
        return CloudSettings(
            provider = provider,
            model = prefs.getString(KEY_MODEL, null) ?: provider.suggestedModel,
            apiKey = prefs.getString(KEY_SECRET, null)?.let(KeyVault::decrypt),
        )
    }

    /** A null [apiKey] keeps the stored one. */
    fun save(provider: CloudProvider, model: String, apiKey: String?) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, provider.name)
            putString(KEY_MODEL, model.trim().ifEmpty { provider.suggestedModel })
            if (!apiKey.isNullOrBlank()) putString(KEY_SECRET, KeyVault.encrypt(apiKey.trim()))
        }.apply()
    }

    /** Forgets the key and the model chosen with it. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_SECRET = "api_key_encrypted"
    }
}
