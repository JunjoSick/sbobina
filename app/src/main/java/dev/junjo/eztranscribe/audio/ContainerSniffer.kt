package dev.junjo.eztranscribe.audio

/**
 * Identifies a container from its first bytes. Needed because some apps share voice notes as
 * `application/octet-stream`, and because WhatsApp's `.opus` files are sometimes labelled
 * `audio/mpeg`.
 */
object ContainerSniffer {

    /** Enough for every signature below, including OpusHead inside the first Ogg page. */
    const val HEADER_BYTES = 128

    fun sniff(header: ByteArray): AudioContainer {
        if (header.size < 4) return AudioContainer.UNKNOWN

        if (header.hasAscii("OggS")) {
            return if (header.containsAscii("OpusHead")) AudioContainer.OGG_OPUS else AudioContainer.OGG_OTHER
        }
        if (header.hasAscii("RIFF") && header.hasAscii("WAVE", offset = 8)) return AudioContainer.WAV
        if (header.hasAscii("fLaC")) return AudioContainer.FLAC
        if (header.hasAscii("#!AMR")) return AudioContainer.AMR
        // ID3 is tested before ftyp on purpose. An ID3v2 tag puts its revision byte where the MP4
        // brand would start, and every byte of "ftyp" is below 0x80, so a hostile or corrupt tag
        // could spell it. The tag at offset 0 is the stronger claim, so it wins.
        if (header.hasAscii("ID3")) return AudioContainer.MP3
        if (header.hasAscii("ftyp", offset = 4)) return AudioContainer.MP4_AAC
        if (header.u8(0) == 0x1A && header.u8(1) == 0x45 && header.u8(2) == 0xDF && header.u8(3) == 0xA3) {
            return AudioContainer.MATROSKA
        }

        // Raw frame syncs. ADTS uses a 12-bit sync with the layer bits zeroed; MPEG audio uses an
        // 11-bit sync and names a layer, so the layer bits separate the two.
        if (header.u8(0) == 0xFF) {
            val b1 = header.u8(1)
            val layerBits = b1 and 0x06
            if (b1 and 0xF0 == 0xF0 && layerBits == 0) return AudioContainer.AAC_ADTS
            if (b1 and 0xE0 == 0xE0 && layerBits != 0) return AudioContainer.MP3
        }

        return AudioContainer.UNKNOWN
    }
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.hasAscii(text: String, offset: Int = 0): Boolean {
    if (offset < 0 || offset + text.length > size) return false
    return text.indices.all { u8(offset + it) == text[it].code }
}

private fun ByteArray.containsAscii(text: String): Boolean {
    for (start in 0..(size - text.length)) {
        if (hasAscii(text, start)) return true
    }
    return false
}
