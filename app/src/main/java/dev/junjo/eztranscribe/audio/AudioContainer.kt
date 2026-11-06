package dev.junjo.eztranscribe.audio

/**
 * The containers a shared voice note can actually arrive in. Messaging apps disagree about
 * MIME types, so the container is decided from the file header, not from what the sender claims.
 */
enum class AudioContainer(val label: String, val canonicalMime: String?) {
    OGG_OPUS("Ogg/Opus", "audio/ogg"),
    OGG_OTHER("Ogg, not Opus", "audio/ogg"),
    MP4_AAC("MP4/AAC", "audio/mp4"),
    MP3("MP3", "audio/mpeg"),
    AAC_ADTS("AAC, raw ADTS", "audio/aac"),
    WAV("WAV/PCM", "audio/wav"),
    FLAC("FLAC", "audio/flac"),
    AMR("AMR", "audio/amr"),
    MATROSKA("Matroska/WebM", "audio/webm"),
    UNKNOWN("unrecognised", null),
    ;

    /**
     * The formats phase 2 commits to decoding and testing. Raw ADTS is here because Signal
     * records voice notes as AAC, and the bench corpus needs Signal clips; MediaCodec decodes it
     * through the same AAC path as MP4.
     */
    val isDecodeTarget: Boolean
        get() = this == OGG_OPUS || this == MP4_AAC || this == AAC_ADTS || this == MP3
}
