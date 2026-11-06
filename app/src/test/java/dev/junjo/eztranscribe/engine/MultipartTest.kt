package dev.junjo.eztranscribe.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {

    @Test
    fun `fields and file are framed by the boundary with CRLF line ends`() {
        val body = Multipart.build(
            boundary = "B",
            fields = listOf("model" to "voxtral-mini-latest", "language" to "it"),
            file = Multipart.File("file", "note.wav", "audio/wav", byteArrayOf(1, 2, 3)),
        )
        val prefix = (
            "--B\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nvoxtral-mini-latest\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nit\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
            ).toByteArray()
        val suffix = "\r\n--B--\r\n".toByteArray()

        assertEquals("multipart/form-data; boundary=B", body.contentType)
        assertArrayEquals(prefix, body.bytes.copyOfRange(0, prefix.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), body.bytes.copyOfRange(prefix.size, prefix.size + 3))
        assertArrayEquals(suffix, body.bytes.copyOfRange(prefix.size + 3, body.bytes.size))
    }

    @Test
    fun `binary file bytes pass through untouched`() {
        val audio = ByteArray(256) { it.toByte() }
        val body = Multipart.build("X", emptyList(), Multipart.File("file", "a.wav", "audio/wav", audio))
        val text = String(body.bytes, Charsets.ISO_8859_1)
        val start = text.indexOf("\r\n\r\n") + 4

        assertArrayEquals(audio, body.bytes.copyOfRange(start, start + audio.size))
        assertTrue(text.endsWith("\r\n--X--\r\n"))
    }
}
