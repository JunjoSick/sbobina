package dev.junjo.eztranscribe.share

import android.net.Uri
import dev.junjo.eztranscribe.audio.AudioContainer

/**
 * One shared voice note, after probing.
 *
 * Reading the bytes and reading the audio format fail independently, and for different reasons,
 * so each carries its own error. Collapsing them loses the distinction that matters most while
 * debugging a share: "the sender gave us something we cannot open" is not the same complaint as
 * "we opened it and did not recognise the container".
 */
data class SharedClip(
    val uri: Uri,
    val displayName: String,
    /** What the sending app called it, which may be wrong or absent. */
    val declaredMime: String?,
    /** What the header says it is, or null when the header could not be read. */
    val container: AudioContainer?,
    /** Why the first bytes could not be read: no access, no such file, empty stream. */
    val readError: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val trackMime: String?,
    /** Why the audio format could not be read, once the bytes themselves were available. */
    val extractorError: String?,
)
