package dev.junjo.eztranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerSnifferTest {

    @Test
    fun `ogg page carrying OpusHead is Opus`() {
        val header = ByteArray(ContainerSniffer.HEADER_BYTES)
        "OggS".toByteArray().copyInto(header)
        "OpusHead".toByteArray().copyInto(header, destinationOffset = 28)

        assertEquals(AudioContainer.OGG_OPUS, ContainerSniffer.sniff(header))
    }

    @Test
    fun `ogg page without OpusHead is not claimed as Opus`() {
        val header = ByteArray(ContainerSniffer.HEADER_BYTES)
        "OggS".toByteArray().copyInto(header)
        "vorbis".toByteArray().copyInto(header, destinationOffset = 29)

        assertEquals(AudioContainer.OGG_OTHER, ContainerSniffer.sniff(header))
    }

    @Test
    fun `ftyp box at offset four is MP4`() {
        val header = ByteArray(32)
        byteArrayOf(0, 0, 0, 0x20).copyInto(header)
        "ftypM4A ".toByteArray().copyInto(header, destinationOffset = 4)

        assertEquals(AudioContainer.MP4_AAC, ContainerSniffer.sniff(header))
    }

    @Test
    fun `ID3 tag is MP3`() {
        assertEquals(AudioContainer.MP3, ContainerSniffer.sniff("ID3\u0003\u0000".toByteArray()))
    }

    @Test
    fun `ID3 tag wins over ftyp bytes that happen to follow it`() {
        // Bytes 4..7 of an ID3v2 header are revision, flags and size; all of "ftyp" fits there.
        val header = ByteArray(16)
        "ID3".toByteArray().copyInto(header)
        header[3] = 0x04
        "ftyp".toByteArray().copyInto(header, destinationOffset = 4)

        assertEquals(AudioContainer.MP3, ContainerSniffer.sniff(header))
    }

    @Test
    fun `bare MPEG frame sync is MP3, not ADTS`() {
        // 0xFB = 11-bit sync, MPEG1, Layer III.
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

        assertEquals(AudioContainer.MP3, ContainerSniffer.sniff(header))
    }

    @Test
    fun `ADTS frame sync with zero layer bits is AAC`() {
        // 0xF1 = 12-bit sync, MPEG4, layer 00, CRC absent.
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte())

        assertEquals(AudioContainer.AAC_ADTS, ContainerSniffer.sniff(header))
    }

    @Test
    fun `RIFF WAVE is WAV`() {
        val header = ByteArray(16)
        "RIFF".toByteArray().copyInto(header)
        "WAVE".toByteArray().copyInto(header, destinationOffset = 8)

        assertEquals(AudioContainer.WAV, ContainerSniffer.sniff(header))
    }

    @Test
    fun `matroska magic is recognised`() {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x01)

        assertEquals(AudioContainer.MATROSKA, ContainerSniffer.sniff(header))
    }

    @Test
    fun `noise and truncated input stay unknown`() {
        assertEquals(AudioContainer.UNKNOWN, ContainerSniffer.sniff(byteArrayOf(1, 2, 3)))
        assertEquals(AudioContainer.UNKNOWN, ContainerSniffer.sniff(ByteArray(64) { 0x5A }))
    }

    @Test
    fun `decode targets are Opus, both AAC framings and MP3`() {
        val targets = AudioContainer.entries.filter { it.isDecodeTarget }

        assertEquals(
            listOf(AudioContainer.OGG_OPUS, AudioContainer.MP4_AAC, AudioContainer.MP3, AudioContainer.AAC_ADTS),
            targets,
        )
    }
}
