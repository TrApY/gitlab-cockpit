package dev.jota.gitlabcockpit.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests the GLC-23 upload-download endpoint of [GitLabApiClient] against a local [HttpServer]:
 * `GET /projects/:id/uploads/:secret/:filename` must return the raw bytes verbatim on 200 (the body
 * is binary, so a byte-faithful roundtrip is exercised with non-UTF-8 bytes) and map a 404 to
 * [GitLabResult.HttpError].
 */
class GitLabProjectUploadApiTest {

    private var server: HttpServer? = null

    @Volatile private var path: String? = null

    @Volatile private var receivedToken: String? = null

    @Volatile private var responseStatus: Int = 200

    @Volatile private var responseBytes: ByteArray = ByteArray(0)

    private val secret = "0123456789abcdef0123456789abcdef"

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123") { exchange: HttpExchange ->
            path = exchange.requestURI.path
            receivedToken = exchange.requestHeaders.getFirst("PRIVATE-TOKEN")
            val length = if (responseBytes.isEmpty()) -1L else responseBytes.size.toLong()
            exchange.sendResponseHeaders(responseStatus, length)
            exchange.responseBody.use { it.write(responseBytes) }
        }
        srv.start()
        server = srv
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server!!.address.port}"

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `getProjectUpload returns the raw bytes verbatim on 200 and sends the token`() {
        // A PNG-like header with bytes that are not valid UTF-8 (0x89, 0xFF) so a byte-faithful
        // roundtrip is distinguishable from a string round-trip.
        responseStatus = 200
        responseBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0xFF.toByte(),
        )
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "tok" }.getProjectUpload(123, secret, "pic.png") }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertArrayEquals(responseBytes, (result as GitLabResult.Success).data)
        assertEquals("tok", receivedToken)
        assertTrue("path was: $path", path!!.endsWith("/uploads/$secret/pic.png"))
    }

    @Test
    fun `getProjectUpload maps 404 to HttpError`() {
        responseStatus = 404
        responseBytes = """{"message":"404 Not Found"}""".toByteArray(StandardCharsets.UTF_8)
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "tok" }.getProjectUpload(123, secret, "missing.png") }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(404, (result as GitLabResult.HttpError).status)
    }
}
