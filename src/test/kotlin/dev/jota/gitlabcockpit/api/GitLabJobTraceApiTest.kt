package dev.jota.gitlabcockpit.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests the F2b job-log endpoints of [GitLabApiClient] against a local [HttpServer]: the plain-text
 * `GET /jobs/:id/trace` (200 full, 206 partial, 200-with-offset byte trimming, 416 empty, non-JSON
 * tolerance, other non-2xx) and `GET /jobs/:id`. The byte-count / byte-trim cases use multi-byte
 * UTF-8 content (U+00F1 'n-tilde', 2 bytes) so a byte-based implementation is distinguishable from a
 * char-based one.
 */
class GitLabJobTraceApiTest {

    private var server: HttpServer? = null

    @Volatile private var method: String? = null

    @Volatile private var path: String? = null

    @Volatile private var rangeHeader: String? = null

    @Volatile private var responseStatus: Int = 200

    @Volatile private var responseBody: String = ""

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123") { exchange: HttpExchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            rangeHeader = exchange.requestHeaders.getFirst("Range")
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
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
    fun `getJobTrace returns the full body from offset 0 and sends no Range header`() {
        responseStatus = 200
        responseBody = "line1\nline2\n"
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 0) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val chunk = (result as GitLabResult.Success).data
        assertEquals("line1\nline2\n", chunk.content)
        assertEquals(responseBody.toByteArray(StandardCharsets.UTF_8).size.toLong(), chunk.nextOffset)
        assertEquals("GET", method)
        assertTrue("path was: $path", path!!.endsWith("/jobs/99/trace"))
        assertNull("no Range header at offset 0, was: $rangeHeader", rangeHeader)
    }

    @Test
    fun `getJobTrace 206 partial sends the Range header and advances nextOffset by UTF-8 bytes`() {
        responseStatus = 206
        responseBody = "ñx" // n-tilde + x -> 2 + 1 = 3 UTF-8 bytes
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 10) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val chunk = (result as GitLabResult.Success).data
        assertEquals("ñx", chunk.content)
        // 10 + 3 bytes = 13 (a char count would wrongly give 12).
        assertEquals(13L, chunk.nextOffset)
        assertEquals("bytes=10-", rangeHeader)
    }

    @Test
    fun `getJobTrace 200 with a non-zero offset trims the leading bytes on the byte array`() {
        responseStatus = 200
        responseBody = "ñabcd" // "ñabcd": ñ = bytes 0-1, a b c d = bytes 2-5 (6 bytes total)
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 2) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val chunk = (result as GitLabResult.Success).data
        // Byte trim at offset 2 drops the whole "ñ" -> "abcd"; a char trim would leave "bcd".
        assertEquals("abcd", chunk.content)
        assertEquals(6L, chunk.nextOffset)
    }

    @Test
    fun `getJobTrace 416 yields an empty fragment at the same offset`() {
        responseStatus = 416
        responseBody = ""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val chunk = (result as GitLabResult.Success).data
        assertEquals("", chunk.content)
        assertEquals(42L, chunk.nextOffset)
    }

    @Test
    fun `getJobTrace tolerates non-JSON body`() {
        responseStatus = 200
        responseBody = "not json { oops ] :: still fine"
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 0) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(responseBody, (result as GitLabResult.Success).data.content)
    }

    @Test
    fun `getJobTrace maps other non-2xx to HttpError`() {
        responseStatus = 500
        responseBody = "boom"
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJobTrace(123, 99, 0) }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(500, (result as GitLabResult.HttpError).status)
    }

    @Test
    fun `getJob hits the job path and parses the job`() {
        responseStatus = 200
        responseBody = """
            {
              "id": 99,
              "name": "unit",
              "stage": "test",
              "status": "running",
              "allow_failure": false,
              "web_url": "https://gitlab.com/g/r/-/jobs/99"
            }
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getJob(123, 99) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val job = (result as GitLabResult.Success).data
        assertEquals(99L, job.id)
        assertEquals("unit", job.name)
        assertEquals("running", job.status)
        assertEquals("GET", method)
        assertTrue("path was: $path", path!!.endsWith("/jobs/99"))
    }
}
