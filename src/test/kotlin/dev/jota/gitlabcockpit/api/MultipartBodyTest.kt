package dev.jota.gitlabcockpit.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/** Pure-logic tests for the hand-built `multipart/form-data` encoder [buildFileMultipart] (GLC-56). */
class MultipartBodyTest {

    @Test
    fun `content type header carries the boundary`() {
        val result = buildFileMultipart("BOUNDARY123", "report.pdf", "application/pdf", byteArrayOf(1, 2, 3))

        assertEquals("multipart/form-data; boundary=BOUNDARY123", result.contentType)
    }

    @Test
    fun `body is a well-formed single file part with the right headers and CRLFs`() {
        val bytes = "hello".toByteArray(StandardCharsets.UTF_8)

        val result = buildFileMultipart("BOUNDARY123", "notes.txt", "text/plain", bytes)

        val expected = (
            "--BOUNDARY123\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--BOUNDARY123--\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(expected, result.body)
    }

    @Test
    fun `raw bytes are preserved verbatim including non-utf8 bytes and the closing boundary follows them`() {
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0x0A)

        val result = buildFileMultipart("B", "blob.bin", "application/octet-stream", binary)

        val header = (
            "--B\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"blob.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val payload = result.body.copyOfRange(header.size, header.size + binary.size)
        assertArrayEquals(binary, payload)

        val footer = result.body.copyOfRange(header.size + binary.size, result.body.size)
        assertArrayEquals("\r\n--B--\r\n".toByteArray(StandardCharsets.UTF_8), footer)
    }
}
