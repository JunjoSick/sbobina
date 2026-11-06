package dev.junjo.eztranscribe.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One finished transcription, kept after the note itself is gone from the main screen. */
data class HistoryEntry(
    val id: String,
    /** The shared file's name, e.g. "PTT-20261031-WA0009.opus". */
    val fileName: String,
    val durationMs: Long?,
    /** Wall-clock time the transcript was made, epoch milliseconds. */
    val createdAt: Long,
    val engineId: String,
    /** How the engine was described when it ran, e.g. "OpenRouter · mistralai/voxtral-mini-transcribe". */
    val engineLabel: String,
    val text: String,
    /** Why another engine stood in, when one did. */
    val note: String? = null,
)

/**
 * Every transcript, newest first, in one JSON file in app-private storage. Nothing else on the
 * phone keeps a copy: deleting an entry here deletes the transcript. Voice notes are small enough,
 * and their transcripts smaller, that rewriting the whole file on each change is fine.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")

    suspend fun load(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    suspend fun save(entries: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) {
            file.delete()
            return@withContext
        }
        val array = JSONArray()
        entries.forEach { array.put(toJson(it)) }
        // Written beside the real file, then moved over it, so a crash mid-write never loses it all.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun toJson(entry: HistoryEntry): JSONObject = JSONObject()
            .put("id", entry.id)
            .put("fileName", entry.fileName)
            .put("durationMs", entry.durationMs ?: JSONObject.NULL)
            .put("createdAt", entry.createdAt)
            .put("engineId", entry.engineId)
            .put("engineLabel", entry.engineLabel)
            .put("text", entry.text)
            .put("note", entry.note ?: JSONObject.NULL)

        fun fromJson(json: JSONObject) = HistoryEntry(
            id = json.getString("id"),
            fileName = json.getString("fileName"),
            durationMs = if (json.isNull("durationMs")) null else json.getLong("durationMs"),
            createdAt = json.getLong("createdAt"),
            engineId = json.getString("engineId"),
            engineLabel = json.getString("engineLabel"),
            text = json.getString("text"),
            note = if (json.isNull("note")) null else json.getString("note"),
        )
    }
}
