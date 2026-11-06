package dev.junjo.eztranscribe.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.junjo.eztranscribe.share.SharedClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads name, size, container and audio format off a shared clip without decoding it. */
class ClipProbe(private val context: Context) {

    suspend fun probe(uri: Uri, intentMime: String?): SharedClip = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, querySize) = queryNameAndSize(uri)
        val size = querySize ?: descriptorLength(uri)
        val header = readHeader(uri)
        val track = readAudioTrack(uri)

        SharedClip(
            uri = uri,
            displayName = name,
            declaredMime = runCatching { resolver.getType(uri) }.getOrNull() ?: intentMime,
            container = header.bytes?.let(ContainerSniffer::sniff),
            readError = header.error,
            sizeBytes = size,
            durationMs = track.info?.durationMs,
            sampleRateHz = track.info?.sampleRateHz,
            channelCount = track.info?.channelCount,
            trackMime = track.info?.mime,
            extractorError = track.error,
        )
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        val fallbackName = uri.lastPathSegment ?: "shared clip"
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameAt >= 0 && !cursor.isNull(nameAt)) cursor.getString(nameAt) else null
                    val size = if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else null
                    return (name ?: fallbackName) to size
                }
            }
        } catch (e: Exception) {
            // Not fatal: a file: URI has no such columns, and some providers refuse the query.
            Log.d(TAG, "no openable columns for $uri", e)
        }
        return fallbackName to null
    }

    /** For providers that omit SIZE, and for file: URIs, which have no openable columns at all. */
    private fun descriptorLength(uri: Uri): Long? = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH }
        }
    } catch (e: Exception) {
        Log.d(TAG, "no descriptor length for $uri", e)
        null
    }

    private fun readHeader(uri: Uri): HeaderResult = try {
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            HeaderResult(error = "the provider returned no stream")
        } else {
            stream.use { input ->
                val buffer = ByteArray(ContainerSniffer.HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled == 0) {
                    HeaderResult(error = "the file is empty")
                } else {
                    HeaderResult(bytes = buffer.copyOf(filled))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the header of $uri", e)
        HeaderResult(error = describe(e))
    }

    private fun readAudioTrack(uri: Uri): TrackResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return TrackResult(error = "no audio track in the file")

            val format = extractor.getTrackFormat(index)
            TrackResult(
                info = TrackInfo(
                    mime = format.getString(MediaFormat.KEY_MIME),
                    durationMs = format.longOrNull(MediaFormat.KEY_DURATION)?.div(1000),
                    sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read the audio format of $uri", e)
            TrackResult(error = describe(e))
        } finally {
            extractor.release()
        }
    }

    /** Exception messages are often null, and the class name alone is the useful part then. */
    private fun describe(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message == null) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
    }

    private class HeaderResult(
        val bytes: ByteArray? = null,
        val error: String? = null,
    )

    private data class TrackInfo(
        val mime: String?,
        val durationMs: Long?,
        val sampleRateHz: Int?,
        val channelCount: Int?,
    )

    private data class TrackResult(
        val info: TrackInfo? = null,
        val error: String? = null,
    )

    private companion object {
        const val TAG = "ClipProbe"
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
